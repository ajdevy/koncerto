package com.flexsentlabs.koncerto.app

import com.flexsentlabs.koncerto.agent.DockerContainerManager
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties
import java.util.concurrent.TimeUnit

@SpringBootApplication(proxyBeanMethods = false)
@ComponentScan(basePackages = ["com.flexsentlabs.koncerto"])
class KoncertoApplication

fun main(args: Array<String>) {
    val localProps = Paths.get("local.properties")
    if (Files.exists(localProps)) {
        val props = Properties()
        FileInputStream(localProps.toFile()).use { props.load(it) }
        for ((key, value) in props) {
            val k = key.toString()
            if (System.getProperty(k) == null && !k.startsWith("kotlin.") && !k.startsWith("sdk.") && !k.startsWith("org.gradle.")) {
                System.setProperty(k, value.toString())
            }
        }
        println("[koncerto] Loaded ${props.size} properties from local.properties")
    }
    val ctx = runApplication<KoncertoApplication>(*args)
    val config = ctx.getBean(ServiceConfig::class.java)
    val logger = ctx.getBean(StructuredLogger::class.java)
    buildDockerAgentImage(config, logger, ctx)
    DockerContainerManager.pruneOldContainers(logger)
    val orchestrator = ctx.getBean(Orchestrator::class.java)
    orchestrator.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        val count = orchestrator.runningAgentsCount()
        if (count > 0) {
            println("\n[koncerto] Shutting down, draining $count agent(s)...")
            orchestrator.requestShutdown()
            runBlocking {
                try {
                    withTimeout(30_000) {
                        while (orchestrator.runningAgentsCount() > 0) {
                            delay(500)
                        }
                    }
                    println("[koncerto] All agents drained")
                } catch (e: Exception) {
                    println("[koncerto] Drain timeout, ${orchestrator.runningAgentsCount()} agent(s) remaining")
                }
            }
        }
    })
}

private fun buildDockerAgentImage(config: ServiceConfig, logger: StructuredLogger, ctx: org.springframework.context.ApplicationContext) {
    val dockerEnabled = config.projects.values.any { it.agent.docker?.enabled != false }
    if (!dockerEnabled) {
        logger.info("docker_image_build_skipped", mapOf("reason" to "docker_disabled"))
        return
    }

    val dockerfile = config.projects.values.firstNotNullOfOrNull { it.agent.docker?.dockerfile } ?: "Dockerfile.agent"
    val image = config.projects.values.firstNotNullOfOrNull { it.agent.docker?.image } ?: "koncerto-agent:latest"

    try {
        val inspectPb = ProcessBuilder("bash", "-lc", "docker image inspect $image 2>/dev/null")
        val inspectP = inspectPb.start()
        val inspectExited = inspectP.waitFor(5, TimeUnit.SECONDS)
        if (inspectExited && inspectP.exitValue() == 0) {
            logger.info("docker_image_exists", mapOf("image" to image))
            return
        }
    } catch (_: Exception) {}

    try {
        logger.info("docker_image_build_starting", mapOf("dockerfile" to dockerfile, "image" to image))
        val pb = ProcessBuilder("docker", "build", "-f", dockerfile, "-t", image, ".")
            .directory(File("/config"))
        pb.inheritIO()
        val p = pb.start()
        val buildCompleted = p.waitFor(1800, TimeUnit.SECONDS)
        if (buildCompleted && p.exitValue() == 0) {
            logger.info("docker_image_build_completed", mapOf("image" to image))
        } else {
            logger.warn("docker_image_build_failed", mapOf("exit_code" to (if (buildCompleted) p.exitValue().toString() else "timeout"), "image" to image))
        }
    } catch (e: Exception) {
        logger.warn("docker_image_build_error", emptyMap(), "error" to (e.message ?: "unknown"))
    }
}
