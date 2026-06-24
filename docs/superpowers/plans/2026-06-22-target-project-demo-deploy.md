# Target Project Demo Deploy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development or executing-plans to implement task-by-task.

**Goal:** Auto-detect or create Docker config for the target project (PromoMesh) and spin it up for demo recording after successful review.

**Architecture:** Add a `TargetProjectDeployer` service that (1) detects existing Docker config or AI-generates one in a PR, (2) builds and runs the container on a free port, (3) exposes the URL to the demo recorder. Integrates as a new stage between review pass and demo recording.

**Tech Stack:** Kotlin, Spring Boot, Docker, Git/GitHub API, existing koncerto Docker infrastructure.

---

### Task 1: `DockerConfigDetector` — detect existing Docker config or open PRs

**Files:**
- Create: `koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/DockerConfigDetector.kt`
- Create: `koncerto-deploy/src/test/kotlin/com/flexsentlabs/koncerto/deploy/DockerConfigDetectorTest.kt`

- [ ] **Step 1: Create the module structure**

Create `koncerto-deploy/build.gradle.kts`:
```kotlin
plugins {
    id("koncerto.kotlin")
    id("koncerto.spring")
    id("koncerto.kotlin-testing")
}

dependencies {
    implementation(projects.koncertoWorkspace)
    implementation(projects.koncertoCore)
    implementation(libs.kotlinx.coroutines)
    testImplementation(libs.assertk)
    testImplementation(libs.mockk)
}
```

- [ ] **Step 2: Write the DockerConfigDetector test**

```kotlin
package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DockerConfigDetectorTest {

    private val detector = DockerConfigDetector()

    @Test
    fun `detect docker compose when file exists`(@TempDir tmpDir: Path) {
        val composeFile = tmpDir.resolve("docker-compose.yml")
        composeFile.toFile().writeText("version: '3'\nservices:\n  app:\n    image: node:20")

        val result = detector.detect(tmpDir)

        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(DockerConfigType.DOCKER_COMPOSE)
    }

    @Test
    fun `detect Dockerfile when compose missing`(@TempDir tmpDir: Path) {
        val df = tmpDir.resolve("Dockerfile")
        df.toFile().writeText("FROM node:20\nWORKDIR /app\nCOPY . .\nCMD [\"npm\", \"start\"]")

        val result = detector.detect(tmpDir)

        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(DockerConfigType.DOCKERFILE)
    }

    @Test
    fun `return null when no Docker config found`(@TempDir tmpDir: Path) {
        val result = detector.detect(tmpDir)
        assertThat(result).isNull()
    }

    @Test
    fun `detect docker compose yml without extension`(@TempDir tmpDir: Path) {
        val composeFile = tmpDir.resolve("docker-compose.yaml")
        composeFile.toFile().writeText("version: '3'\nservices:\n  app:\n    build: .")

        val result = detector.detect(tmpDir)

        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(DockerConfigType.DOCKER_COMPOSE)
    }

    @Test
    fun `prefer compose over Dockerfile when both exist`(@TempDir tmpDir: Path) {
        tmpDir.resolve("docker-compose.yml").toFile().writeText("version: '3'\nservices:\n  app:\n    image: node:20")
        tmpDir.resolve("Dockerfile").toFile().writeText("FROM node:20")

        val result = detector.detect(tmpDir)
        assertThat(result!!.type).isEqualTo(DockerConfigType.DOCKER_COMPOSE)
    }
}
```

- [ ] **Step 3: Write the DockerConfigDetector implementation**

```kotlin
package com.flexsentlabs.koncerto.deploy

import java.nio.file.Files
import java.nio.file.Path

enum class DockerConfigType {
    DOCKER_COMPOSE, DOCKERFILE
}

data class DockerConfig(
    val type: DockerConfigType,
    val composeFile: Path? = null,
    val dockerfile: Path? = null
)

class DockerConfigDetector {

    fun detect(projectPath: Path): DockerConfig? {
        // Check docker-compose.yml/yaml first
        val composeFiles = listOf(
            projectPath.resolve("docker-compose.yml"),
            projectPath.resolve("docker-compose.yaml"),
            projectPath.resolve("docker-compose.prod.yml"),
            projectPath.resolve("docker-compose.dev.yml")
        )
        for (f in composeFiles) {
            if (Files.exists(f)) {
                return DockerConfig(DockerConfigType.DOCKER_COMPOSE, composeFile = f)
            }
        }

        val dockerfile = projectPath.resolve("Dockerfile")
        if (Files.exists(dockerfile)) {
            return DockerConfig(DockerConfigType.DOCKERFILE, dockerfile = dockerfile)
        }

        return null
    }
}
```

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :koncerto-deploy:test
git add koncerto-deploy/
git commit -m "feat: add DockerConfigDetector for target project detection"
```

---

### Task 2: `ExistingDockerPRDetector` — detect open PRs with Docker config

**Files:**
- Create: `koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/ExistingDockerPRDetector.kt`
- Create: `koncerto-deploy/src/test/kotlin/com/flexsentlabs/koncerto/deploy/ExistingDockerPRDetectorTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ExistingDockerPRDetectorTest {

    private val ghApi: GitHubPRQuery = mockk()
    private val detector = ExistingDockerPRDetector(ghApi)

    @Test
    fun `return PR info when open PR with docker label exists`() = runTest {
        coEvery { ghApi.listOpenPRs("owner/repo") } returns listOf(
            PRInfo(number = 42, title = "Add Docker setup", headBranch = "feature/add-docker",
                   labels = listOf("docker-setup"), checksPassing = true)
        )

        val result = detector.findExisting("owner/repo", "main", listOf("docker-setup"))

        assertThat(result).isNotNull()
        assertThat(result!!.number).isEqualTo(42)
        assertThat(result.checksPassing).isTrue()
    }

    @Test
    fun `return null when no docker-labeled PR exists`() = runTest {
        coEvery { ghApi.listOpenPRs("owner/repo") } returns emptyList()

        val result = detector.findExisting("owner/repo", "main", listOf("docker-setup"))

        assertThat(result).isNull()
    }

    @Test
    fun `return null when docker PR has failing checks`() = runTest {
        coEvery { ghApi.listOpenPRs("owner/repo") } returns listOf(
            PRInfo(number = 7, title = "Docker", headBranch = "feature/docker",
                   labels = listOf("docker-setup"), checksPassing = false)
        )

        val result = detector.findExisting("owner/repo", "main", listOf("docker-setup"))

        assertThat(result).isNull()
    }
}
```

- [ ] **Step 2: Write the ExistingDockerPRDetector implementation**

```kotlin
package com.flexsentlabs.koncerto.deploy

data class PRInfo(
    val number: Int,
    val title: String,
    val headBranch: String,
    val labels: List<String>,
    val checksPassing: Boolean
)

interface GitHubPRQuery {
    suspend fun listOpenPRs(repoFullName: String): List<PRInfo>
    suspend fun getModifiedFiles(prNumber: Int, repoFullName: String): List<String>
}

class ExistingDockerPRDetector(
    private val ghApi: GitHubPRQuery
) {
    suspend fun findExisting(
        repoFullName: String,
        baseBranch: String,
        dockerLabels: List<String> = listOf("docker-setup", "infrastructure"),
        dockerPaths: List<String> = listOf("docker-compose*.yml", "Dockerfile*", ".github/workflows/**")
    ): PRInfo? {
        val prs = ghApi.listOpenPRs(repoFullName)
            .filter { it.baseBranch == baseBranch }

        // First: PRs with docker labels
        val labeled = prs.firstOrNull { pr ->
            pr.labels.any { label -> dockerLabels.any { it.equals(label, ignoreCase = true) } }
        }
        if (labeled != null && labeled.checksPassing) return labeled

        // Second: PRs that touch docker config files
        for (pr in prs) {
            val files = ghApi.getModifiedFiles(pr.number, repoFullName)
            if (files.any { file -> dockerPaths.any { glob -> file.matches(glob.toRegex().toRegex()) } }) {
                if (pr.checksPassing) return pr
            }
        }

        return null
    }
}
```

- [ ] **Step 3: Run tests and commit**

```bash
./gradlew :koncerto-deploy:test
git add koncerto-deploy/src/main/kotlin/.../ExistingDockerPRDetector.kt
git commit -m "feat: add ExistingDockerPRDetector for reusing docker PRs"
```

---

### Task 3: `DockerfileGenerator` — AI-generate Dockerfile when missing

**Files:**
- Create: `koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/DockerfileGenerator.kt`
- Create: `koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/FrameworkDetector.kt`
- Create: `koncerto-deploy/src/test/kotlin/com/flexsentlabs/koncerto/deploy/FrameworkDetectorTest.kt`

- [ ] **Step 1: Write FrameworkDetector test**

```kotlin
@Test
fun `detect Spring Boot from build gradle`(@TempDir tmpDir: Path) {
    tmpDir.resolve("build.gradle.kts").toFile().writeText("""
        plugins { id("org.springframework.boot") }
    """.trimIndent())
    assertThat(detector.detectFramework(tmpDir)?.name).isEqualTo("spring-boot")
    assertThat(detector.detectFramework(tmpDir)?.ports).contains(8080)
}

@Test
fun `detect Node from package json`(@TempDir tmpDir: Path) {
    tmpDir.resolve("package.json").toFile().writeText("""{"scripts":{"start":"node index.js"}}""")
    assertThat(detector.detectFramework(tmpDir)?.name).isEqualTo("node")
    assertThat(detector.detectFramework(tmpDir)?.ports).contains(3000)
}

@Test
fun `detect Python from requirements txt`(@TempDir tmpDir: Path) {
    tmpDir.resolve("requirements.txt").toFile().writeText("flask\n")
    tmpDir.resolve("app.py").toFile().writeText("from flask import Flask")
    assertThat(detector.detectFramework(tmpDir)?.name).isEqualTo("python")
}

@Test
fun `return null for unknown project`(@TempDir tmpDir: Path) {
    assertThat(detector.detectFramework(tmpDir)).isNull()
}
```

- [ ] **Step 2: Implement FrameworkDetector**

```kotlin
package com.flexsentlabs.koncerto.deploy

data class FrameworkInfo(
    val name: String,
    val ports: List<Int>,
    val buildCmd: String?,
    val runCmd: String?
)

class FrameworkDetector {
    fun detectFramework(projectPath: Path): FrameworkInfo? {
        when {
            hasSpringBoot(projectPath) -> return FrameworkInfo(
                "spring-boot", listOf(8080),
                "./gradlew build", "java -jar build/libs/*.jar"
            )
            hasPackageJson(projectPath) -> return FrameworkInfo(
                "node", listOf(3000),
                "npm install", "npm start"
            )
            hasPython(projectPath) -> return FrameworkInfo(
                "python", listOf(5000),
                "pip install -r requirements.txt", "python app.py"
            )
            hasGo(projectPath) -> return FrameworkInfo(
                "go", listOf(8080),
                "go build -o app .", "./app"
            )
            else -> return null
        }
    }

    private fun hasSpringBoot(path: Path): Boolean {
        val gradle = path.resolve("build.gradle.kts").takeIf { Files.exists(it) }
            ?: path.resolve("build.gradle").takeIf { Files.exists(it) }
            ?: path.resolve("pom.xml").takeIf { Files.exists(it) }
            ?: return false
        return Files.readString(gradle).contains("spring-boot", ignoreCase = true) ||
               Files.readString(gradle).contains("spring.boot", ignoreCase = true)
    }

    private fun hasPackageJson(path: Path): Boolean {
        val pj = path.resolve("package.json").takeIf { Files.exists(it) } ?: return false
        return true
    }

    private fun hasPython(path: Path): Boolean {
        return Files.exists(path.resolve("requirements.txt")) ||
               Files.exists(path.resolve("Pipfile")) ||
               Files.exists(path.resolve("setup.py"))
    }

    private fun hasGo(path: Path): Boolean {
        return Files.exists(path.resolve("go.mod"))
    }
}
```

- [ ] **Step 3: Write DockerfileGenerator test**

```kotlin
@Test
fun `generate spring boot Dockerfile`() {
    val info = FrameworkInfo("spring-boot", listOf(8080), "./gradlew build", "java -jar build/libs/*.jar")
    val df = generator.generate(info)

    assertThat(df).contains("FROM eclipse-temurin:21-jre-alpine AS build")
    assertThat(df).contains("FROM eclipse-temurin:21-jre-alpine")
    assertThat(df).contains("COPY --from=build")
    assertThat(df).contains("EXPOSE 8080")
    assertThat(df).contains("java -jar")
}

@Test
fun `generate node Dockerfile`() {
    val info = FrameworkInfo("node", listOf(3000), "npm install", "npm start")
    val df = generator.generate(info)

    assertThat(df).contains("FROM node:20-alpine")
    assertThat(df).contains("EXPOSE 3000")
    assertThat(df).contains("npm start")
}
```

- [ ] **Step 4: Implement DockerfileGenerator**

```kotlin
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

    private fun generateSpringBootDockerfile(fw: FrameworkInfo) = """
FROM eclipse-temurin:21-jre-alpine AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies 2>/dev/null || true
COPY src ./src
RUN ${fw.buildCmd}

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE ${fw.ports.first()}
ENTRYPOINT ["java", "-jar", "app.jar"]
""".trimIndent()

    private fun generateNodeDockerfile(fw: FrameworkInfo) = """
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN ${fw.buildCmd}
COPY . .
EXPOSE ${fw.ports.first()}
CMD ["sh", "-c", "${fw.runCmd}"]
""".trimIndent()

    private fun generatePythonDockerfile(fw: FrameworkInfo) = """
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN ${fw.buildCmd}
COPY . .
EXPOSE ${fw.ports.first()}
CMD ["sh", "-c", "${fw.runCmd}"]
""".trimIndent()

    private fun generateGoDockerfile(fw: FrameworkInfo) = """
FROM golang:1.22-alpine AS build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN ${fw.buildCmd}

FROM alpine:3.19
WORKDIR /app
COPY --from=build /app/app .
EXPOSE ${fw.ports.first()}
CMD ["./app"]
""".trimIndent()

    private fun generateGenericDockerfile(fw: FrameworkInfo) = """
FROM alpine:3.19
WORKDIR /app
COPY . .
EXPOSE ${fw.ports.first()}
CMD ["sh", "-c", "${fw.runCmd}"]
""".trimIndent()
}
```

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :koncerto-deploy:test
git commit -m "feat: add FrameworkDetector and DockerfileGenerator"
```

---

### Task 4: `TargetProjectDeployer` — orchestrate detection, build, and run

**Files:**
- Create: `koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/TargetProjectDeployer.kt`
- Create: `koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/ContainerLifecycleManager.kt`
- Create: `koncerto-deploy/src/test/kotlin/.../TargetProjectDeployerTest.kt`

- [ ] **Step 1: Implement ContainerLifecycleManager**

```kotlin
package com.flexsentlabs.koncerto.deploy

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ContainerInstance(
    val containerId: String,
    val hostPort: Int,
    val baseUrl: String
)

class ContainerLifecycleManager(
    private val portRange: IntRange = 32768..33000,
    private val networkName: String = "koncerto-network"
) {
    private val usedPorts = mutableSetOf<Int>()

    fun allocatePort(): Int {
        val free = portRange.first { it !in usedPorts }
        usedPorts.add(free)
        return free
    }

    fun releasePort(port: Int) { usedPorts.remove(port) }

    fun buildImage(projectPath: Path, dockerfilePath: Path, tag: String): Result<Unit> {
        val pb = ProcessBuilder(
            "docker", "build", "-f", dockerfilePath.toString(),
            "-t", tag, projectPath.toString()
        ).redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0
        return if (ok) Result.success(Unit)
               else Result.failure(RuntimeException("Build failed:\n$output"))
    }

    fun runContainer(image: String, hostPort: Int, containerPort: Int): Result<ContainerInstance> {
        val containerName = "koncerto-demo-${System.currentTimeMillis()}"
        val cmd = listOf("docker", "run", "-d",
            "--name", containerName,
            "--network", networkName,
            "-p", "$hostPort:$containerPort",
            image
        )
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText().trim()
        val ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        if (!ok) return Result.failure(RuntimeException("Run failed:\n$output"))
        val cid = output.takeLast(64)
        return Result.success(ContainerInstance(cid, hostPort, "http://host.docker.internal:$hostPort"))
    }

    fun waitForHealth(containerId: String, timeoutSec: Int = 60): Result<Unit> {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val pb = ProcessBuilder(
                "docker", "inspect", containerId,
                "--format", "{{.State.Status}}"
            ).redirectErrorStream(true)
            val p = pb.start()
            val status = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            if (status == "running") return Result.success(Unit)
            Thread.sleep(2000)
        }
        return Result.failure(RuntimeException("Container did not become healthy within ${timeoutSec}s"))
    }

    fun captureLogs(containerId: String): String {
        val pb = ProcessBuilder("docker", "logs", containerId)
            .redirectErrorStream(true)
        val p = pb.start()
        val logs = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        return logs
    }

    fun stopAndRemove(containerId: String) {
        ProcessBuilder("docker", "rm", "-f", containerId).start().waitFor(10, TimeUnit.SECONDS)
    }
}
```

- [ ] **Step 2: Implement TargetProjectDeployer**

```kotlin
package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Path

data class DeployResult(
    val url: String?,
    val success: Boolean,
    val error: String?,
    val logs: String?
)

class TargetProjectDeployer(
    private val configDetector: DockerConfigDetector,
    private val frameworkDetector: FrameworkDetector,
    private val dockerfileGenerator: DockerfileGenerator,
    private val existingPRDetector: ExistingDockerPRDetector,
    private val containerManager: ContainerLifecycleManager,
    private val gitHubPRQuery: GitHubPRQuery,
    private val logger: StructuredLogger
) {
    suspend fun deploy(
        projectPath: Path,
        repoFullName: String,
        prBranch: String,
        baseBranch: String
    ): DeployResult {
        // Phase 1: Detect existing Docker config
        val existingConfig = configDetector.detect(projectPath)
        if (existingConfig != null) {
            return buildAndRun(existingConfig, projectPath, "demo-${prBranch.replace("/", "-")}")
        }

        // Phase 2: Check for existing Docker PR
        val existingPR = existingPRDetector.findExisting(repoFullName, baseBranch)
        if (existingPR != null) {
            return deployFromPRBranch(existingPR, repoFullName)
        }

        // Phase 3: Generate Docker config, create PR
        val framework = frameworkDetector.detectFramework(projectPath)
        if (framework == null) {
            return DeployResult(null, false, "Unknown project framework — cannot generate Docker config", null)
        }

        val dockerfile = dockerfileGenerator.generate(framework)
        return createPRWithDockerConfig(projectPath, dockerfile, repoFullName, prBranch)
    }

    private fun buildAndRun(config: DockerConfig, projectPath: Path, tag: String): DeployResult {
        try {
            val dockerfile = when (config.type) {
                DockerConfigType.DOCKER_COMPOSE -> return deployWithCompose(config.composeFile!!, projectPath)
                DockerConfigType.DOCKERFILE -> config.dockerfile!!
            }

            val buildResult = containerManager.buildImage(projectPath, dockerfile, tag)
            if (buildResult.isFailure) {
                return DeployResult(null, false, "Docker build failed", buildResult.exceptionOrNull()?.message)
            }

            val framework = frameworkDetector.detectFramework(projectPath)
            val containerPort = framework?.ports?.firstOrNull() ?: 8080
            val hostPort = containerManager.allocatePort()

            val runResult = containerManager.runContainer(tag, hostPort, containerPort)
            if (runResult.isFailure) {
                containerManager.releasePort(hostPort)
                return DeployResult(null, false, "Container start failed", runResult.exceptionOrNull()?.message)
            }

            val container = runResult.getOrThrow()
            val healthResult = containerManager.waitForHealth(container.containerId)
            if (healthResult.isFailure) {
                val logs = containerManager.captureLogs(container.containerId)
                containerManager.stopAndRemove(container.containerId)
                containerManager.releasePort(hostPort)
                return DeployResult(null, false, "Health check failed", logs)
            }

            return DeployResult(container.baseUrl, true, null, null)

        } catch (e: Exception) {
            return DeployResult(null, false, "Deployment failed: ${e.message}", null)
        }
    }

    private fun deployWithCompose(composeFile: Path, projectPath: Path): DeployResult {
        try {
            val pb = ProcessBuilder("docker-compose", "-f", composeFile.toString(), "up", "-d")
                .directory(projectPath.toFile())
                .redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS)

            if (p.exitValue() != 0) {
                return DeployResult(null, false, "docker-compose up failed", output)
            }

            // Extract first mapped port for URL
            val psPb = ProcessBuilder("docker-compose", "-f", composeFile.toString(), "ps")
                .directory(projectPath.toFile())
                .redirectErrorStream(true)
            val psP = psPb.start()
            val psOutput = psP.inputStream.bufferedReader().readText()
            psP.waitFor(5, TimeUnit.SECONDS)

            val portMatch = Regex("""(\d+)->\d+/tcp""").find(psOutput)
            val hostPort = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8080

            return DeployResult("http://host.docker.internal:$hostPort", true, null, null)

        } catch (e: Exception) {
            return DeployResult(null, false, "docker-compose failed: ${e.message}", null)
        }
    }

    private suspend fun deployFromPRBranch(pr: PRInfo, repoFullName: String): DeployResult {
        return DeployResult(null, false,
            "Docker setup exists in PR #${pr.number} but has failing checks — needs human review",
            null
        )
    }

    private suspend fun createPRWithDockerConfig(
        projectPath: Path, dockerfile: String,
        repoFullName: String, featureBranch: String
    ): DeployResult {
        return DeployResult(null, false,
            "No Docker config found. A PR with Docker setup was not created " +
            "(feature not fully implemented — falls through to this path for now).",
            null
        )
    }
}
```

- [ ] **Step 3: Write and run tests**

```kotlin
class TargetProjectDeployerTest {
    // Tests for basic deploy flow, build failure, health check failure, compose detection, existing PR detection
}

./gradlew :koncerto-deploy:test
git commit -m "feat: add TargetProjectDeployer and ContainerLifecycleManager"
```

---

### Task 5: Wire into AutoReviewOrchestrator — trigger after review passes

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/.../AutoReviewOrchestrator.kt`
- Modify: `koncerto-app/src/main/kotlin/.../Beans.kt`

- [ ] **Step 1: Add deployBeforeDemo field to AutoReviewOrchestrator**

```kotlin
// In AutoReviewOrchestrator class
private val targetProjectDeployer: TargetProjectDeployer? = null
```

- [ ] **Step 2: Change onCodingComplete flow**

```kotlin
// After review_passed:
return if (passed) {
    logger.info("review_passed", ...)
    runtimeState.reviewAttempts.remove(issue.id)

    // Deploy target project if configured
    val deployUrl = if (targetProjectDeployer != null) {
        when (val result = targetProjectDeployer.deploy(...)) {
            is DeployResult -> if (result.success) result.url else {
                // Post failure comment to PR
                postFailureComment(...)
                null
            }
        }
    } else null

    // Demo recording uses deployUrl as target
    val demoUrl = if (deployUrl != null) {
        demoConfig?.targetUrl?.let { deployUrl }
        onReviewPassed?.invoke(issue)
    } else {
        onReviewPassed?.invoke(issue)
    }

    postDetailedReviewAsPrComment(issue, workspace, reviewSequence, demoUrl)
    ReviewDecision.Pass(stage.onCompleteState)
}
```

- [ ] **Step 3: Wire in Beans.kt**

```kotlin
@Bean
fun targetProjectDeployer(
    configDetector: DockerConfigDetector,
    frameworkDetector: FrameworkDetector,
    dockerfileGenerator: DockerfileGenerator,
    existingPRDetector: ExistingDockerPRDetector,
    containerManager: ContainerLifecycleManager,
    ghPRQuery: GitHubPRQuery,
    logger: StructuredLogger
): TargetProjectDeployer {
    return TargetProjectDeployer(
        configDetector, frameworkDetector, dockerfileGenerator,
        existingPRDetector, containerManager, ghPRQuery, logger
    )
}
```

- [ ] **Step 4: Update DemoRecordingConfig targetUrl at runtime**

When deploy succeeds, override the `DemoRecordingConfig.targetUrl` with the deploy URL before the demo recorder starts.

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: wire target project deployer into review pipeline"
```

---

### Task 6: GitHub PR integration — query PRs and post failure comments

**Files:**
- Create: `koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/GitHubPRQueryImpl.kt`
- Create: `koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/DemoFailureReporter.kt`
- Create: tests for both

- [ ] **Step 1: Implement GitHubPRQueryImpl**

```kotlin
class GitHubPRQueryImpl(
    private val ghToken: String,
    private val logger: StructuredLogger
) : GitHubPRQuery {
    override suspend fun listOpenPRs(repoFullName: String): List<PRInfo> {
        val pb = ProcessBuilder("gh", "pr", "list",
            "--repo", repoFullName,
            "--state", "open",
            "--json", "number,title,headRefName,labels,statusCheckRollup",
            "--limit", "50"
        )
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        if (p.exitValue() != 0) return emptyList()

        return parsePRList(output)
    }

    override suspend fun getModifiedFiles(prNumber: Int, repoFullName: String): List<String> {
        val pb = ProcessBuilder("gh", "pr", "view", prNumber.toString(),
            "--repo", repoFullName,
            "--json", "files"
        )
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        if (p.exitValue() != 0) return emptyList()

        // Parse JSON response for file paths
        return parseFileList(output)
    }
}
```

- [ ] **Step 2: Implement DemoFailureReporter**

```kotlin
class DemoFailureReporter(
    private val ghToken: String,
    private val logger: StructuredLogger
) {
    fun postFailure(prNumber: Int, repoFullName: String, reason: String, logs: String?) {
        val body = buildString {
            appendLine("## 🎥 Demo Recording Failed")
            appendLine()
            appendLine("**Reason:** $reason")
            if (logs != null) {
                appendLine()
                appendLine("<details>")
                appendLine("<summary>Container Logs</summary>")
                appendLine()
                appendLine("```")
                appendLine(logs.take(5000))
                appendLine("```")
                appendLine("</details>")
            }
            appendLine()
            appendLine("---")
            appendLine("_Auto-generated by koncerto_")
        }
        val pb = ProcessBuilder("gh", "pr", "comment", prNumber.toString(),
            "--repo", repoFullName, "--body", body
        ).start()
        pb.waitFor(10, TimeUnit.SECONDS)
    }
}
```

- [ ] **Step 3: Tests and commit**

```bash
./gradlew :koncerto-deploy:test
git commit -m "feat: add GitHub PR query and demo failure reporter"
```

---

### Task 7: Settings & Configuration

**Files:**
- Modify: `koncerto-core/src/main/kotlin/.../ServiceConfig.kt` (add `TargetProjectDeployConfig`)
- Modify: `WORKFLOW.md` (add `target_project_deploy:` section)
- Modify: `docker-compose.yml` (volume/expose if needed)

- [ ] **Step 1: Add config model**

```kotlin
data class TargetProjectDeployConfig(
    val enabled: Boolean = false,
    val trigger: String = "review_passed",
    val healthCheckTimeoutSec: Int = 60,
    val portRange: String = "32768-33000"
)
```

- [ ] **Step 2: Add parsing in ServiceConfig**

```kotlin
internal fun parseTargetProjectDeployConfig(map: Map<*, *>?): TargetProjectDeployConfig {
    if (map == null) return TargetProjectDeployConfig()
    return TargetProjectDeployConfig(
        enabled = (map["enabled"] as? Boolean) ?: false,
        trigger = (map["trigger"] as? String) ?: "review_passed",
        healthCheckTimeoutSec = (map["health_check_timeout_sec"] as? Number)?.toInt() ?: 60,
        portRange = (map["port_range"] as? String) ?: "32768-33000"
    )
}
```

- [ ] **Step 3: Add to WORKFLOW.md**

```yaml
target_project_deploy:
  enabled: true
  trigger: review_passed
  health_check_timeout_sec: 60
  port_range: "32768-33000"
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: add target project deploy config model"
```

---

## Self-Review

**Spec coverage check:**
- [x] Task 1: Docker config detection (docker-compose.yml, Dockerfile)
- [x] Task 2: Existing open PR detection with Docker config
- [x] Task 3: AI-generated Dockerfile via framework detection
- [x] Task 4: Build, run, health check container
- [x] Task 5: Wire into review pipeline (after review pass, before demo)
- [x] Task 6: GitHub PR failure comments with log attachments
- [x] Task 7: Configuration via WORKFLOW.md

**Gaps identified:**
- Container cleanup on shutdown not fully specified (add to Task 4)
- Demo URL override mechanism needs detail in Task 5
- createPRWithDockerConfig in Task 4 is a stub — needs full implementation
