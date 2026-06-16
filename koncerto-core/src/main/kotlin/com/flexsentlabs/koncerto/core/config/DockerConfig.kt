package com.flexsentlabs.koncerto.core.config

@kotlinx.serialization.Serializable
data class DockerConfig(
    val enabled: Boolean = true,
    val image: String = "koncerto-agent:latest",
    val cpu: String = "auto",
    val memory: String = "auto",
    val network: Boolean = true,
    val dockerfile: String = "Dockerfile.agent"
)
