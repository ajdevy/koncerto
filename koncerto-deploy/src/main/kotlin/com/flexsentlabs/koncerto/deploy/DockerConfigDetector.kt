package com.flexsentlabs.koncerto.deploy

import java.nio.file.Files
import java.nio.file.Path

enum class DockerConfigType {
    DOCKER_COMPOSE, DOCKERFILE
}

data class DetectedDockerConfig(
    val type: DockerConfigType,
    val composeFile: Path? = null,
    val dockerfile: Path? = null
)

class DockerConfigDetector {

    fun detect(projectPath: Path): DetectedDockerConfig? {
        val composeFiles = listOf(
            projectPath.resolve("docker-compose.yml"),
            projectPath.resolve("docker-compose.yaml"),
            projectPath.resolve("docker-compose.prod.yml"),
            projectPath.resolve("docker-compose.dev.yml")
        )
        for (f in composeFiles) {
            if (Files.exists(f)) {
                return DetectedDockerConfig(DockerConfigType.DOCKER_COMPOSE, composeFile = f)
            }
        }

        val dockerfile = projectPath.resolve("Dockerfile")
        if (Files.exists(dockerfile)) {
            return DetectedDockerConfig(DockerConfigType.DOCKERFILE, dockerfile = dockerfile)
        }

        return null
    }
}
