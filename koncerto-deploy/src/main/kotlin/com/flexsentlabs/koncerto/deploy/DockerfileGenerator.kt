package com.flexsentlabs.koncerto.deploy

class DockerfileGenerator {

    fun generate(framework: FrameworkInfo): String {
        return when (framework.name) {
            "spring-boot" -> generateSpringBootDockerfile(framework)
            "node" -> generateNodeDockerfile(framework)
            "python" -> generatePythonDockerfile(framework)
            "go" -> generateGoDockerfile(framework)
            else -> generateGenericDockerfile(framework)
        }
    }

    private fun generateSpringBootDockerfile(fw: FrameworkInfo): String = """
FROM eclipse-temurin:21-jre-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies 2>/dev/null || true
COPY src ./src
RUN ${fw.buildCmd ?: "./gradlew build"}

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE ${fw.ports.first()}
ENTRYPOINT ["java", "-jar", "app.jar"]
""".trimIndent()

    private fun generateNodeDockerfile(fw: FrameworkInfo): String = """
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN ${fw.buildCmd ?: "npm install"}
COPY . .
EXPOSE ${fw.ports.first()}
CMD ["sh", "-c", "${fw.runCmd ?: "npm start"}"]
""".trimIndent()

    private fun generatePythonDockerfile(fw: FrameworkInfo): String {
        val pipMirror = "https://pypi.tuna.tsinghua.edu.cn/simple/"
        val pipTrusted = "pypi.tuna.tsinghua.edu.cn"
        val pipFlags = "--index-url $pipMirror --trusted-host $pipTrusted"
        // If buildCmd is set, replace the bare pip prefix so mirror flags apply;
        // otherwise install the project directory directly.
        val installTarget = fw.buildCmd
            ?.removePrefix("pip install --default-timeout 60 --retries 10")
            ?.trim()
            ?: "."
        return """
FROM python:3.12-slim
WORKDIR /app
COPY . .
RUN pip install $pipFlags hatchling setuptools wheel && \
    pip install --no-build-isolation $pipFlags $installTarget
EXPOSE ${fw.ports.first()}
CMD ["sh", "-c", "${fw.runCmd ?: "python app.py"}"]
""".trimIndent()
    }

    private fun generateGoDockerfile(fw: FrameworkInfo): String = """
FROM golang:1.22-alpine AS build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN ${fw.buildCmd ?: "go build -o app ."}

FROM alpine:3.19
WORKDIR /app
COPY --from=build /app/app .
EXPOSE ${fw.ports.first()}
CMD ["./app"]
""".trimIndent()

    private fun generateGenericDockerfile(fw: FrameworkInfo): String = """
FROM alpine:3.19
WORKDIR /app
COPY . .
EXPOSE ${fw.ports.first()}
CMD ["sh", "-c", "${fw.runCmd ?: "./app"}"]
""".trimIndent()
}
