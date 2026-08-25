package com.flexsentlabs.koncerto.demo.recorder.android

/**
 * Command construction and output parsing for the Android recorder.
 *
 * Kept apart from the recorder itself so the decisions that matter — which device is usable, what
 * exactly gets executed, whether a run passed — are unit-tested, while only the process plumbing
 * around them is excluded from coverage.
 */
object AdbCommands {

    /** Longest a single `adb shell screenrecord` can run; the tool stops itself at this point. */
    const val MAX_SCREENRECORD_SECONDS = 180

    /** A device or emulator visible to adb. */
    data class AdbDevice(val serial: String, val state: String) {
        val isUsable: Boolean get() = state == "device"
        val isEmulator: Boolean get() = serial.startsWith("emulator-")
    }

    /**
     * Parses `adb devices` output.
     *
     * States other than `device` matter: `unauthorized` means the host's key was never accepted and
     * `offline` means the emulator is still booting. Both look like "a device is there" to a naive
     * check and then fail every command, so they are surfaced rather than filtered away.
     */
    fun parseDevices(output: String): List<AdbDevice> = output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            AdbDevice(parts[0], parts[1])
        }
        .toList()

    /** The first usable device, preferring a real one over an emulator when both are attached. */
    fun selectDevice(devices: List<AdbDevice>): AdbDevice? =
        devices.filter { it.isUsable }.minByOrNull { if (it.isEmulator) 1 else 0 }

    /** A human-readable reason no recording can happen, or null when [devices] contains a usable one. */
    fun unusableReason(devices: List<AdbDevice>): String? = when {
        devices.isEmpty() ->
            "no Android device or emulator is attached (adb devices is empty)"
        devices.none { it.isUsable } ->
            "no usable Android device: " + devices.joinToString(", ") { "${it.serial} is ${it.state}" }
        else -> null
    }

    fun installCommand(serial: String, apkPath: String): List<String> =
        listOf("adb", "-s", serial, "install", "-r", "-g", apkPath)

    fun screenrecordCommand(serial: String, devicePath: String, width: Int, height: Int, seconds: Int): List<String> =
        listOf(
            "adb", "-s", serial, "shell", "screenrecord",
            "--bit-rate", "4000000",
            "--size", "${width}x$height",
            "--time-limit", seconds.coerceIn(1, MAX_SCREENRECORD_SECONDS).toString(),
            devicePath
        )

    /**
     * Stops recording with SIGINT, not SIGKILL: screenrecord writes the MP4 moov atom on interrupt,
     * so a killed process leaves an unplayable file.
     */
    fun stopRecordingCommand(serial: String): List<String> =
        listOf("adb", "-s", serial, "shell", "pkill", "-INT", "-f", "screenrecord")

    fun pullCommand(serial: String, devicePath: String, localPath: String): List<String> =
        listOf("adb", "-s", serial, "pull", devicePath, localPath)

    fun removeCommand(serial: String, devicePath: String): List<String> =
        listOf("adb", "-s", serial, "shell", "rm", "-f", devicePath)

    fun maestroCommand(serial: String, flowPath: String): List<String> =
        listOf("maestro", "--device", serial, "test", "--format", "junit", flowPath)

    /**
     * Why a Maestro run failed, in a form the scenario-repair loop can act on.
     *
     * Maestro reports a failed assertion with a non-zero exit and the failing step in its output, so
     * the message names the step rather than just the code — a repair prompt needs to know which
     * element was not found, not that "something failed".
     */
    fun failureReason(exitCode: Int, output: String): String? {
        if (exitCode == 0) return null
        val failingStep = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("[FAILED]") || it.contains("Assertion is false") || it.contains("Element not found") }
        return if (failingStep != null) {
            "maestro flow failed (exit $exitCode): ${failingStep.take(300)}"
        } else {
            "maestro flow failed (exit $exitCode): ${output.trim().takeLast(300).ifBlank { "no output" }}"
        }
    }
}
