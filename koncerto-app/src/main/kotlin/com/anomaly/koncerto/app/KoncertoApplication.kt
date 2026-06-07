package com.anomaly.koncerto.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ComponentScan(basePackages = ["com.anomaly.koncerto"])
class KoncertoApplication

fun main(args: Array<String>) {
    val webType = if (args.any { it.startsWith("--port") }) "reactive" else "none"
    val expanded = args.toMutableList()
    expanded.add(0, "--spring.main.web-application-type=$webType")
    runApplication<KoncertoApplication>(*expanded.toTypedArray())
}
