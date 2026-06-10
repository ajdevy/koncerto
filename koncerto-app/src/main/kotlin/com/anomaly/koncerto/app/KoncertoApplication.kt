package com.anomaly.koncerto.app

import com.anomaly.koncerto.orchestrator.Orchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication(proxyBeanMethods = false)
@ComponentScan(basePackages = ["com.anomaly.koncerto"])
class KoncertoApplication

fun main(args: Array<String>) {
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
