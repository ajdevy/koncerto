package com.anomaly.koncerto.app

import com.anomaly.koncerto.orchestrator.Orchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties

@SpringBootApplication(proxyBeanMethods = false)
@ComponentScan(basePackages = ["com.anomaly.koncerto"])
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
    val orchestrator = ctx.getBean(Orchestrator::class.java)
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
