package com.flexsentlabs.koncerto.demo.recorder.android

import org.junit.jupiter.api.Test

class AdbCommandsTest {

    @Test
    fun `parses adb devices output`() {
        val devices = AdbCommands.parseDevices(
            """
            List of devices attached
            emulator-5554	device
            R58M12ABCDE	device
            """.trimIndent()
        )

        assert(devices.size == 2)
        assert(devices[0] == AdbCommands.AdbDevice("emulator-5554", "device"))
        assert(devices[0].isEmulator)
        assert(!devices[1].isEmulator)
    }

    @Test
    fun `skips the header, daemon chatter and malformed lines`() {
        val devices = AdbCommands.parseDevices(
            """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            emulator-5554	device

            garbage
            """.trimIndent()
        )

        assert(devices.map { it.serial } == listOf("emulator-5554"))
    }

    @Test
    fun `non-device states are surfaced rather than filtered away`() {
        // unauthorized and offline both look like "a device is there" but fail every command.
        val devices = AdbCommands.parseDevices(
            "List of devices attached\nemulator-5554\toffline\nR58M12ABCDE\tunauthorized"
        )

        assert(devices.size == 2)
        assert(devices.none { it.isUsable })
        assert(AdbCommands.selectDevice(devices) == null)
    }

    @Test
    fun `selectDevice prefers a real device over an emulator`() {
        val devices = listOf(
            AdbCommands.AdbDevice("emulator-5554", "device"),
            AdbCommands.AdbDevice("R58M12ABCDE", "device")
        )

        assert(AdbCommands.selectDevice(devices)?.serial == "R58M12ABCDE")
    }

    @Test
    fun `selectDevice falls back to an emulator and ignores unusable ones`() {
        assert(AdbCommands.selectDevice(listOf(AdbCommands.AdbDevice("emulator-5554", "device")))?.serial == "emulator-5554")
        assert(AdbCommands.selectDevice(listOf(AdbCommands.AdbDevice("x", "offline"))) == null)
        assert(AdbCommands.selectDevice(emptyList()) == null)
    }

    @Test
    fun `unusableReason names the concrete blocker`() {
        assert(AdbCommands.unusableReason(emptyList())!!.contains("adb devices is empty"))

        val unauthorised = AdbCommands.unusableReason(listOf(AdbCommands.AdbDevice("R58M", "unauthorized")))!!
        assert(unauthorised.contains("R58M is unauthorized"))

        assert(AdbCommands.unusableReason(listOf(AdbCommands.AdbDevice("emulator-5554", "device"))) == null)
    }

    @Test
    fun `install grants permissions and replaces an existing build`() {
        val cmd = AdbCommands.installCommand("emulator-5554", "/tmp/app.apk")

        assert(cmd == listOf("adb", "-s", "emulator-5554", "install", "-r", "-g", "/tmp/app.apk"))
    }

    @Test
    fun `screenrecord is bounded by the tool's own hard limit`() {
        val cmd = AdbCommands.screenrecordCommand("emulator-5554", "/sdcard/x.mp4", 1280, 720, 600)

        assert(cmd.contains("--time-limit"))
        assert(cmd[cmd.indexOf("--time-limit") + 1] == AdbCommands.MAX_SCREENRECORD_SECONDS.toString())
        assert(cmd.contains("1280x720"))
        assert(cmd.last() == "/sdcard/x.mp4")
    }

    @Test
    fun `screenrecord duration is clamped to at least one second`() {
        val cmd = AdbCommands.screenrecordCommand("s", "/sdcard/x.mp4", 720, 1280, 0)

        assert(cmd[cmd.indexOf("--time-limit") + 1] == "1")
    }

    @Test
    fun `recording is stopped with SIGINT so the mp4 is finalised`() {
        // A killed screenrecord never writes the moov atom and the pulled file is unplayable.
        assert(AdbCommands.stopRecordingCommand("emulator-5554") ==
            listOf("adb", "-s", "emulator-5554", "shell", "pkill", "-INT", "-f", "screenrecord"))
    }

    @Test
    fun `pull and remove target the same device path`() {
        assert(AdbCommands.pullCommand("s", "/sdcard/x.mp4", "/tmp/out.mp4") ==
            listOf("adb", "-s", "s", "pull", "/sdcard/x.mp4", "/tmp/out.mp4"))
        assert(AdbCommands.removeCommand("s", "/sdcard/x.mp4") ==
            listOf("adb", "-s", "s", "shell", "rm", "-f", "/sdcard/x.mp4"))
    }

    @Test
    fun `maestro is pinned to the selected device`() {
        val cmd = AdbCommands.maestroCommand("emulator-5554", "/tmp/flow.yaml")

        assert(cmd == listOf("maestro", "--device", "emulator-5554", "test", "--format", "junit", "/tmp/flow.yaml"))
    }

    @Test
    fun `a zero exit is a pass`() {
        assert(AdbCommands.failureReason(0, "anything at all") == null)
    }

    @Test
    fun `a failure names the failing step rather than just the code`() {
        val reason = AdbCommands.failureReason(1, "Running flow\n[FAILED] Assertion is false: \"Welcome\" is visible\nDone")!!

        assert(reason.contains("exit 1"))
        assert(reason.contains("Welcome"))
    }

    @Test
    fun `element-not-found output is recognised as the failing step`() {
        val reason = AdbCommands.failureReason(1, "Element not found: id=send-button")!!

        assert(reason.contains("send-button"))
    }

    @Test
    fun `a failure with no recognisable step falls back to the output tail`() {
        assert(AdbCommands.failureReason(2, "some unstructured crash output")!!.contains("crash output"))
        assert(AdbCommands.failureReason(2, "   ")!!.contains("no output"))
    }
}
