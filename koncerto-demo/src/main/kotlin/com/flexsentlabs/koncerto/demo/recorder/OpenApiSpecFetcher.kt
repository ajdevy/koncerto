package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.recorder.api.OpenApiInventory
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches the deployed target's OpenAPI document so API demo scenarios can be grounded on endpoints
 * that actually exist — the server-side counterpart of [DomInventoryCrawler].
 *
 * Runs curl inside the same ephemeral `--rm` recorder container pattern the other recorders use,
 * joined to the target's Docker network, because the target is only reachable at its internal
 * `http://<container>:<port>` address. The probe is GET-only.
 *
 * Best-effort by contract: every failure path returns null, so a service that publishes no spec
 * simply falls back to ungrounded generation instead of blocking the demo. Like the recorders, this
 * container/OS-dependent class is excluded from coverage; the compaction it feeds is unit-tested.
 */
class OpenApiSpecFetcher(
    private val logger: StructuredLogger,
    private val processBuilderFactory: ((network: String, image: String, targetUrl: String) -> ProcessBuilder)? = null
) {
    /** Returns the raw spec document, or null when the target publishes none we can reach. */
    suspend fun fetch(targetUrl: String): String? = withContext(Dispatchers.IO) {
        val containerName = parseContainerName(targetUrl)
        if (containerName == null && processBuilderFactory == null) {
            logger.warn("openapi_spec_not_internal_url", mapOf("target_url" to targetUrl))
            return@withContext null
        }
        try {
            val pb = processBuilderFactory?.invoke("test", "test", targetUrl) ?: run {
                val image = RecorderImage().ensureAvailable().getOrElse { e ->
                    logger.warn("openapi_spec_image_unavailable", mapOf("error" to (e.message ?: "unknown")))
                    return@withContext null
                }
                val network = resolveDockerNetwork(requireNotNull(containerName)) ?: run {
                    logger.warn("openapi_spec_network_unresolved", mapOf("container" to containerName))
                    return@withContext null
                }
                ProcessBuilder(
                    listOf(
                        "docker", "run", "--rm",
                        "--network", network,
                        image, "sh", "-c", probeCommand(targetUrl)
                    )
                ).redirectError(ProcessBuilder.Redirect.DISCARD)
            }

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn("openapi_spec_timeout", mapOf("target_url" to targetUrl))
                return@withContext null
            }
            val spec = stdout.trim().takeIf { it.startsWith("{") }
            if (spec == null) {
                logger.info("openapi_spec_absent", mapOf("target_url" to targetUrl))
            } else {
                logger.info("openapi_spec_found", mapOf("target_url" to targetUrl, "bytes" to spec.length.toString()))
            }
            spec
        } catch (e: Exception) {
            logger.warn("openapi_spec_error", mapOf("error" to (e.message ?: "unknown")))
            null
        }
    }

    /** Probes each candidate path in turn and prints the first JSON document one of them returns. */
    internal fun probeCommand(targetUrl: String): String {
        val base = targetUrl.trimEnd('/')
        val paths = OpenApiInventory.CANDIDATE_PATHS.joinToString(" ") { "'$it'" }
        return "for p in $paths; do " +
            "body=\$(curl -sS --max-time 10 -H 'Accept: application/json' \"$base\$p\" 2>/dev/null); " +
            "case \"\$body\" in '{'*) printf '%s' \"\$body\"; exit 0;; esac; " +
            "done; exit 1"
    }

    private fun parseContainerName(targetUrl: String): String? = try {
        java.net.URI(targetUrl).host?.takeIf { it.isNotBlank() && it != "localhost" }
    } catch (_: Exception) {
        null
    }

    private fun resolveDockerNetwork(containerName: String): String? = try {
        val p = ProcessBuilder("docker", "inspect", containerName, "--format", "{{.HostConfig.NetworkMode}}")
            .redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) output.takeIf { it.isNotBlank() } else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
    }
}
