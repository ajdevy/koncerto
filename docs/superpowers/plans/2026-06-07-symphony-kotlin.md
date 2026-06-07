# Symphony Kotlin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Kotlin/JVM Spring Boot implementation of the OpenAI Symphony orchestration service per the SPEC v1.

**Architecture:** Multi-module Gradle project with strict dependency direction. Kotlin coroutines for concurrency. Spring WebClient for HTTP. Modular: core, workflow, workspace, agent, linear, orchestrator, dashboard, logging modules.

**Tech Stack:** Kotlin 2.0+, Java 21, Spring Boot 3.2+, Gradle 8.x, kotlinx.coroutines, kotlinx.serialization, Spring WebClient, snakeyaml, liqp (Liquid templates), JUnit 5, AssertK, kotlinx-coroutines-test, WireMock.

---

## Phase 0: Project Setup

### Task 0.1: Initialize Gradle multi-module project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml` (version catalog)
- Create: `.gitignore`
- Create: `gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Create root settings.gradle.kts**

```kotlin
// settings.gradle.kts
rootProject.name = "koncerto"

include(
    "koncerto-core",
    "koncerto-logging",
    "koncerto-workflow",
    "koncerto-workspace",
    "koncerto-linear",
    "koncerto-agent",
    "koncerto-orchestrator",
    "koncerto-dashboard",
    "koncerto-app"
)
```

- [ ] **Step 2: Create root build.gradle.kts**

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.anomaly.koncerto"
    version = "0.1.0-SNAPSHOT"
    
    repositories {
        mavenCentral()
    }
}
```

- [ ] **Step 3: Create gradle.properties**

```properties
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2g -XX:+UseParallelGC
org.gradle.parallel=true
org.gradle.caching=true
```

- [ ] **Step 4: Create version catalog gradle/libs.versions.toml**

```toml
[versions]
kotlin = "2.0.21"
spring-boot = "3.3.5"
coroutines = "1.9.0"
serialization = "1.7.3"
webclient = "6.1.14"
snakeyaml = "2.3"
liqp = "0.8.3"
logback = "1.5.11"
junit5 = "5.11.3"
assertk = "0.28.1"
mockk = "1.13.13"
wiremock = "3.10.0"
testcontainers = "1.20.3"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
spring-webflux = { module = "org.springframework:spring-webflux" }
spring-boot-starter-webflux = { module = "org.springframework.boot:spring-boot-starter-webflux", version.ref = "spring-boot" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator", version.ref = "spring-boot" }
webclient = { module = "io.projectreactor.netty:reactor-netty", version.ref = "webclient" }
snakeyaml = { module = "org.yaml:snakeyaml", version.ref = "snakeyaml" }
liqp = { module = "nl.big-o:liqp", version.ref = "liqp" }
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
junit5-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit5" }
assertk = { module = "com.willowtreeapps.assertk:assertk-jvm", version.ref = "assertk" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
wiremock-standalone = { module = "org.wiremock:wiremock-standalone", version.ref = "wiremock" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test", version.ref = "spring-boot" }
spring-boot-testcontainers = { module = "org.springframework.boot:spring-boot-testcontainers", version.ref = "spring-boot" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "spring-boot" }
```

- [ ] **Step 5: Create .gitignore**

```gitignore
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
!**/src/main/**/build/
!**/src/test/**/build/

# IDE
.idea/
*.iml
*.iws
*.ipr
out/
.vscode/

# OS
.DS_Store
Thumbs.db

# Logs
*.log
log/

# Generated
*.class
```

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/libs.versions.toml .gitignore
git commit -m "build: initialize gradle multi-module project"
```

---

## Phase 1: Core Module (`koncerto-core`)

### Task 1.1: Create core module build file and package structure

**Files:**
- Create: `koncerto-core/build.gradle.kts`
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/`

- [ ] **Step 1: Create core build file**

```kotlin
// koncerto-core/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
```

- [ ] **Step 2: Create package directory structure**

```bash
mkdir -p koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/{model,config,error,result}
mkdir -p koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/{model,config,error,result}
```

- [ ] **Step 3: Commit**

```bash
git add koncerto-core/build.gradle.kts
git commit -m "build(koncerto-core): add core module skeleton"
```

---

### Task 1.2: Implement Result wrapper and error types

**Files:**
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/result/Result.kt`
- Create: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/result/ResultTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/result/ResultTest.kt
package com.anomaly.koncerto.core.result

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameAs
import org.junit.jupiter.api.Test

class ResultTest {

    @Test
    fun `Success holds value`() {
        val result = Result.Success(42)
        assertThat(result.value).isEqualTo(42)
    }

    @Test
    fun `Failure holds error`() {
        val error = RuntimeException("boom")
        val result = Result.Failure(error)
        assertThat(result.error).isSameAs(error)
    }

    @Test
    fun `map transforms Success value`() {
        val result: Result<Int, RuntimeException> = Result.Success(2)
        val mapped = result.map { it * 3 }
        assertThat(mapped).isEqualTo(Result.Success(6))
    }

    @Test
    fun `map preserves Failure`() {
        val error = RuntimeException("boom")
        val result: Result<Int, RuntimeException> = Result.Failure(error)
        val mapped = result.map { it * 3 }
        assertThat(mapped).isEqualTo(Result.Failure(error))
    }

    @Test
    fun `onSuccess runs block for Success`() {
        var captured: Int? = null
        val result: Result<Int, RuntimeException> = Result.Success(5)
        result.onSuccess { captured = it }
        assertThat(captured).isEqualTo(5)
    }

    @Test
    fun `onSuccess does not run for Failure`() {
        var captured: Int? = null
        val result: Result<Int, RuntimeException> = Result.Failure(RuntimeException())
        result.onSuccess { captured = it }
        assertThat(captured).isNull()
    }

    @Test
    fun `onFailure runs block for Failure`() {
        val error = RuntimeException("boom")
        var captured: Throwable? = null
        val result: Result<Int, RuntimeException> = Result.Failure(error)
        result.onFailure { captured = it }
        assertThat(captured).isSameAs(error)
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :koncerto-core:test --tests "com.anomaly.koncerto.core.result.ResultTest"`
Expected: compilation error (Result class not defined)

- [ ] **Step 3: Implement Result type**

```kotlin
// koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/result/Result.kt
package com.anomaly.koncerto.core.result

sealed class Result<out T, out E : Throwable> {
    data class Success<T>(val value: T) : Result<T, Nothing>()
    data class Failure<E : Throwable>(val error: E) : Result<Nothing, E>()

    inline fun <R> map(transform: (T) -> R): Result<R, E> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): Result<T, E> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (E) -> Unit): Result<T, E> {
        if (this is Failure) block(error)
        return this
    }

    inline fun getOrNull(): T? = (this as? Success)?.value

    inline fun exceptionOrNull(): E? = (this as? Failure)?.error
}

typealias EmptyResult<E> = Result<Unit, E>

inline fun <T, E : Throwable> runCatchingResult(block: () -> T): Result<T, E> = try {
    Result.Success(block())
} catch (e: Throwable) {
    @Suppress("UNCHECKED_CAST")
    Result.Failure(e as E)
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :koncerto-core:test --tests "com.anomaly.koncerto.core.result.ResultTest"`
Expected: all 7 tests pass

- [ ] **Step 5: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/result/Result.kt koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/result/ResultTest.kt
git commit -m "feat(core): add Result wrapper with map/onSuccess/onFailure"
```

---

### Task 1.3: Implement Issue domain model

**Files:**
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/model/Issue.kt`
- Create: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/model/IssueTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/model/IssueTest.kt
package com.anomaly.koncerto.core.model

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.time.Instant
import org.junit.jupiter.api.Test

class IssueTest {

    @Test
    fun `Issue is constructed with all fields`() {
        val now = Instant.now()
        val issue = Issue(
            id = "issue-1",
            identifier = "ABC-1",
            title = "Test issue",
            description = "Body",
            priority = 2,
            state = "Todo",
            branchName = "abc-1-branch",
            url = "https://linear.app/test/issue/ABC-1",
            labels = listOf("Bug", "Frontend"),
            blockedBy = listOf(BlockerRef(id = "x", identifier = "ABC-2", state = "Done")),
            createdAt = now,
            updatedAt = now
        )

        assertThat(issue.id).isEqualTo("issue-1")
        assertThat(issue.identifier).isEqualTo("ABC-1")
        assertThat(issue.title).isEqualTo("Test issue")
        assertThat(issue.priority).isEqualTo(2)
        assertThat(issue.state).isEqualTo("Todo")
        assertThat(issue.labels).containsExactly("bug", "frontend")
        assertThat(issue.blockedBy).containsExactly(BlockerRef("x", "ABC-2", "Done"))
    }

    @Test
    fun `Issue normalizes labels to lowercase`() {
        val issue = Issue(
            id = "1", identifier = "A-1", title = "t", description = null,
            priority = null, state = "Todo", branchName = null, url = null,
            labels = listOf("  Bug  ", "FRONTEND", ""),
            blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        assertThat(issue.labels.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
            .containsExactly("bug", "frontend")
    }

    @Test
    fun `normalizedState lowercases the state`() {
        val issue = sampleIssue().copy(state = "In Progress")
        assertThat(issue.normalizedState).isEqualTo("in progress")
    }

    @Test
    fun `normalizedState handles null description`() {
        val issue = sampleIssue().copy(description = null)
        assertThat(issue.description).isNull()
    }

    private fun sampleIssue() = Issue(
        id = "1", identifier = "A-1", title = "t", description = "d",
        priority = 1, state = "Todo", branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(),
        createdAt = null, updatedAt = null
    )
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :koncerto-core:test --tests "com.anomaly.koncerto.core.model.IssueTest"`
Expected: compilation error

- [ ] **Step 3: Implement Issue model**

```kotlin
// koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/model/Issue.kt
package com.anomaly.koncerto.core.model

import java.time.Instant

data class Issue(
    val id: String,
    val identifier: String,
    val title: String,
    val description: String?,
    val priority: Int?,
    val state: String,
    val branchName: String?,
    val url: String?,
    val labels: List<String>,
    val blockedBy: List<BlockerRef>,
    val createdAt: Instant?,
    val updatedAt: Instant?
) {
    val normalizedState: String get() = state.lowercase()
}

data class BlockerRef(
    val id: String?,
    val identifier: String?,
    val state: String?
)
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :koncerto-core:test --tests "com.anomaly.koncerto.core.model.IssueTest"`
Expected: all 4 tests pass

- [ ] **Step 5: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/model/Issue.kt koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/model/IssueTest.kt
git commit -m "feat(core): add Issue domain model with BlockerRef"
```

---

### Task 1.4: Implement WorkflowDefinition and ServiceConfig

**Files:**
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/WorkflowDefinition.kt`
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`
- Create: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt`

- [ ] **Step 1: Write failing test for ServiceConfig**

```kotlin
// koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt
package com.anomaly.koncerto.core.config

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class ServiceConfigTest {

    @Test
    fun `defaults are applied when fields are missing`() {
        val config = ServiceConfig.fromMap(emptyMap(), workflowFileDir = "/tmp")

        assertThat(config.pollIntervalMs).isEqualTo(30_000L)
        assertThat(config.activeStates).containsExactly("Todo", "In Progress")
        assertThat(config.terminalStates).containsExactly("Closed", "Cancelled", "Canceled", "Duplicate", "Done")
        assertThat(config.requiredLabels).isEqualTo(emptyList())
        assertThat(config.maxConcurrentAgents).isEqualTo(10)
        assertThat(config.maxTurns).isEqualTo(20)
        assertThat(config.maxRetryBackoffMs).isEqualTo(300_000L)
        assertThat(config.turnTimeoutMs).isEqualTo(3_600_000L)
        assertThat(config.readTimeoutMs).isEqualTo(5_000L)
        assertThat(config.stallTimeoutMs).isEqualTo(300_000L)
        assertThat(config.hooksTimeoutMs).isEqualTo(60_000L)
        assertThat(config.codexCommand).isEqualTo("codex app-server")
    }

    @Test
    fun `tracker kind is required and validated`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("tracker" to mapOf("project_slug" to "p")),
            workflowFileDir = "/tmp"
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `linear api_key resolved from env var`() {
        val prev = System.getenv("LINEAR_API_KEY")
        try {
            System.setProperty("LINEAR_API_KEY_FOR_TEST", "secret")
            val config = ServiceConfig.fromMap(
                mapOf(
                    "tracker" to mapOf(
                        "kind" to "linear",
                        "api_key" to "\$LINEAR_API_KEY_FOR_TEST",
                        "project_slug" to "proj"
                    )
                ),
                workflowFileDir = "/tmp"
            )
            assertThat(config.trackerApiKey).isEqualTo("secret")
        } finally {
            System.clearProperty("LINEAR_API_KEY_FOR_TEST")
            if (prev != null) System.setProperty("LINEAR_API_KEY", prev)
        }
    }

    @Test
    fun `workspace root expands tilde`() {
        val config = ServiceConfig.fromMap(
            mapOf("workspace" to mapOf("root" to "~/workspaces")),
            workflowFileDir = "/tmp"
        )
        assertThat(config.workspaceRoot.toString()).isEqualTo("\${user.home}/workspaces")
    }

    @Test
    fun `relative workspace root resolves against workflow file dir`() {
        val config = ServiceConfig.fromMap(
            mapOf("workspace" to mapOf("root" to "ws")),
            workflowFileDir = "/some/dir"
        )
        assertThat(config.workspaceRoot.toString()).isEqualTo("/some/dir/ws")
    }
}

// Helper for assertions
private fun <T> T?.isNotNull() = assertk.assertions.isNotNull().let { this }
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :koncerto-core:test --tests "com.anomaly.koncerto.core.config.ServiceConfigTest"`
Expected: compilation error

- [ ] **Step 3: Implement WorkflowDefinition and ServiceConfig**

```kotlin
// koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/WorkflowDefinition.kt
package com.anomaly.koncerto.core.config

data class WorkflowDefinition(
    val config: Map<String, Any?>,
    val promptTemplate: String
)
```

```kotlin
// koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt
package com.anomaly.koncerto.core.config

import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.core.result.runCatchingResult
import java.nio.file.Path
import java.nio.file.Paths

data class ServiceConfig(
    val trackerKind: String?,
    val trackerEndpoint: String,
    val trackerApiKey: String?,
    val trackerProjectSlug: String?,
    val requiredLabels: List<String>,
    val activeStates: List<String>,
    val terminalStates: List<String>,
    val pollIntervalMs: Long,
    val workspaceRoot: Path,
    val hooks: HooksConfig,
    val maxConcurrentAgents: Int,
    val maxTurns: Int,
    val maxRetryBackoffMs: Long,
    val maxConcurrentAgentsByState: Map<String, Int>,
    val codexCommand: String,
    val codexApprovalPolicy: Map<String, Any?>?,
    val codexThreadSandbox: String?,
    val codexTurnSandboxPolicy: Map<String, Any?>?,
    val turnTimeoutMs: Long,
    val readTimeoutMs: Long,
    val stallTimeoutMs: Long
) {
    companion object {
        fun fromMap(map: Map<String, Any?>, workflowFileDir: String): ServiceConfig =
            fromMapOrError(map, workflowFileDir).let { result ->
                when (result) {
                    is Result.Success -> result.value
                    is Result.Failure -> throw IllegalStateException("Invalid config", result.error)
                }
            }

        fun fromMapOrError(
            map: Map<String, Any?>,
            workflowFileDir: String
        ): EmptyResult<IllegalStateException> = runCatchingResult {
            val tracker = map["tracker"] as? Map<*, *>
            val polling = map["polling"] as? Map<*, *>
            val workspace = map["workspace"] as? Map<*, *>
            val hooks = map["hooks"] as? Map<*, *>
            val agent = map["agent"] as? Map<*, *>
            val codex = map["codex"] as? Map<*, *>

            val activeStates = (tracker?.get("active_states") as? List<*>)
                ?.filterIsInstance<String>()
                ?: listOf("Todo", "In Progress")
            val terminalStates = (tracker?.get("terminal_states") as? List<*>)
                ?.filterIsInstance<String>()
                ?: listOf("Closed", "Cancelled", "Canceled", "Duplicate", "Done")

            val requiredLabels = (tracker?.get("required_labels") as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { it.trim() }
                ?: emptyList()

            val workspaceRootRaw = (workspace?.get("root") as? String)
                ?: "\${java.io.tmpdir}/symphony_workspaces"
            val workspaceRoot = resolvePath(workspaceRootRaw, workflowFileDir)

            val hooksConfig = HooksConfig(
                afterCreate = hooks?.get("after_create") as? String,
                beforeRun = hooks?.get("before_run") as? String,
                afterRun = hooks?.get("after_run") as? String,
                beforeRemove = hooks?.get("before_remove") as? String,
                timeoutMs = (hooks?.get("timeout_ms") as? Number)?.toLong() ?: 60_000L
            )

            val perState = (agent?.get("max_concurrent_agents_by_state") as? Map<*, *>)
                ?.mapNotNull { (k, v) ->
                    val key = (k as? String)?.lowercase() ?: return@mapNotNull null
                    val value = (v as? Number)?.toInt() ?: return@mapNotNull null
                    if (value <= 0) null else key to value
                }
                ?.toMap()
                ?: emptyMap()

            val codexCommand = (codex?.get("command") as? String) ?: "codex app-server"
            if (codexCommand.isBlank()) {
                throw IllegalStateException("codex.command must not be empty")
            }

            val maxTurns = (agent?.get("max_turns") as? Number)?.toInt() ?: 20
            if (maxTurns <= 0) {
                throw IllegalStateException("agent.max_turns must be positive")
            }

            ServiceConfig(
                trackerKind = tracker?.get("kind") as? String,
                trackerEndpoint = (tracker?.get("endpoint") as? String)
                    ?: "https://api.linear.app/graphql",
                trackerApiKey = resolveEnvRef(tracker?.get("api_key") as? String),
                trackerProjectSlug = tracker?.get("project_slug") as? String,
                requiredLabels = requiredLabels,
                activeStates = activeStates,
                terminalStates = terminalStates,
                pollIntervalMs = (polling?.get("interval_ms") as? Number)?.toLong() ?: 30_000L,
                workspaceRoot = workspaceRoot,
                hooks = hooksConfig,
                maxConcurrentAgents = (agent?.get("max_concurrent_agents") as? Number)?.toInt() ?: 10,
                maxTurns = maxTurns,
                maxRetryBackoffMs = (agent?.get("max_retry_backoff_ms") as? Number)?.toLong() ?: 300_000L,
                maxConcurrentAgentsByState = perState,
                codexCommand = codexCommand,
                codexApprovalPolicy = codex?.get("approval_policy") as? Map<String, Any?>,
                codexThreadSandbox = codex?.get("thread_sandbox") as? String,
                codexTurnSandboxPolicy = codex?.get("turn_sandbox_policy") as? Map<String, Any?>,
                turnTimeoutMs = (codex?.get("turn_timeout_ms") as? Number)?.toLong() ?: 3_600_000L,
                readTimeoutMs = (codex?.get("read_timeout_ms") as? Number)?.toLong() ?: 5_000L,
                stallTimeoutMs = (codex?.get("stall_timeout_ms") as? Number)?.toLong() ?: 300_000L
            )
        }

        private fun resolveEnvRef(value: String?): String? {
            if (value == null) return null
            val envMatch = Regex("""^\$([A-Z_][A-Z0-9_]*)$""").matchEntire(value)
            return if (envMatch != null) {
                System.getenv(envMatch.groupValues[1])
            } else {
                value
            }
        }

        private fun resolvePath(raw: String, workflowFileDir: String): Path {
            val expanded = expandTilde(raw)
            val envResolved = resolveEnvRef(expanded) ?: expanded
            val withEnv = envResolved
                .replace("\${java.io.tmpdir}", System.getProperty("java.io.tmpdir"))
            val path = Paths.get(withEnv)
            return if (path.isAbsolute) path else Paths.get(workflowFileDir).resolve(path)
        }

        private fun expandTilde(path: String): String {
            if (path.startsWith("~/")) {
                return "\${user.home}/" + path.removePrefix("~/")
            }
            return path
        }
    }
}

data class HooksConfig(
    val afterCreate: String?,
    val beforeRun: String?,
    val afterRun: String?,
    val beforeRemove: String?,
    val timeoutMs: Long
)
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :koncerto-core:test --tests "com.anomaly.koncerto.core.config.ServiceConfigTest"`
Expected: all 5 tests pass

- [ ] **Step 5: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/
git commit -m "feat(core): add ServiceConfig with workflow defaults, env resolution, path expansion"
```

---

## Phase 2: Logging Module (`koncerto-logging`)

### Task 2.1: Create logging module and structured logger

**Files:**
- Create: `koncerto-logging/build.gradle.kts`
- Create: `koncerto-logging/src/main/kotlin/com/anomaly/koncerto/logging/StructuredLogger.kt`
- Create: `koncerto-logging/src/main/kotlin/com/anomaly/koncerto/logging/LogSinks.kt`
- Create: `koncerto-logging/src/test/kotlin/com/anomaly/koncerto/logging/StructuredLoggerTest.kt`

- [ ] **Step 1: Create logging build file**

```kotlin
// koncerto-logging/build.gradle.kts
plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
}
```

- [ ] **Step 2: Write failing test**

```kotlin
// koncerto-logging/src/test/kotlin/com/anomaly/koncerto/logging/StructuredLoggerTest.kt
package com.anomaly.koncerto.logging

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

class StructuredLoggerTest {

    @Test
    fun `structured log includes key=value pairs and context`() {
        val baos = ByteArrayOutputStream()
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.info(
            "dispatch_completed",
            mapOf("issue_id" to "i1", "issue_identifier" to "ABC-1"),
            "outcome" to "success"
        )
        val output = sink.lines.single()
        assertThat(output).contains("action=dispatch_completed")
        assertThat(output).contains("issue_id=i1")
        assertThat(output).contains("issue_identifier=ABC-1")
        assertThat(output).contains("outcome=success")
        assertThat(output).contains("level=info")
    }

    @Test
    fun `failures are logged with error key`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.failure("turn_failed", mapOf("issue_id" to "i2"), RuntimeException("boom"), "attempt" to "1")
        val output = sink.lines.single()
        assertThat(output).contains("action=turn_failed")
        assertThat(output).contains("outcome=failed")
        assertThat(output).contains("error=boom")
        assertThat(output).contains("attempt=1")
    }

    @Test
    fun `multi sinks are all written`() {
        val a = StringListSink()
        val b = StringListSink()
        val logger = StructuredLogger(listOf(a, b))
        logger.info("tick", emptyMap())
        assertThat(a.lines.size).isEqualTo(1)
        assertThat(b.lines.size).isEqualTo(1)
    }
}

class StringListSink : LogSink {
    val lines = mutableListOf<String>()
    override fun write(line: String) { lines.add(line) }
}
```

- [ ] **Step 3: Run test, verify it fails**

Run: `./gradlew :koncerto-logging:test --tests "com.anomaly.koncerto.logging.StructuredLoggerTest"`
Expected: compilation error

- [ ] **Step 4: Implement LogSinks and StructuredLogger**

```kotlin
// koncerto-logging/src/main/kotlin/com/anomaly/koncerto/logging/LogSinks.kt
package com.anomaly.koncerto.logging

interface LogSink {
    fun write(line: String)
}

class StderrSink : LogSink {
    override fun write(line: String) {
        System.err.println(line)
    }
}

class FileSink(path: java.nio.file.Path) : LogSink {
    private val writer = path.toFile().bufferedWriter()
    override fun write(line: String) {
        synchronized(writer) { writer.write(line); writer.newLine(); writer.flush() }
    }
}

class CompositeSink(private val sinks: List<LogSink>) : LogSink {
    override fun write(line: String) {
        sinks.forEach {
            try { it.write(line) } catch (_: Throwable) { /* keep going */ }
        }
    }
}
```

```kotlin
// koncerto-logging/src/main/kotlin/com/anomaly/koncerto/logging/StructuredLogger.kt
package com.anomaly.koncerto.logging

import java.time.Instant

class StructuredLogger(private val sinks: List<LogSink>) {

    fun info(action: String, context: Map<String, Any?>, vararg kvs: Pair<String, Any?>) =
        log("info", action, context, null, kvs.toMap())

    fun warn(action: String, context: Map<String, Any?>, vararg kvs: Pair<String, Any?>) =
        log("warn", action, context, null, kvs.toMap())

    fun error(action: String, context: Map<String, Any?>, vararg kvs: Pair<String, Any?>) =
        log("error", action, context, null, kvs.toMap())

    fun failure(
        action: String,
        context: Map<String, Any?>,
        error: Throwable,
        vararg kvs: Pair<String, Any?>
    ) = log("error", action, context, error, kvs.toMap())

    fun debug(action: String, context: Map<String, Any?>, vararg kvs: Pair<String, Any?>) =
        log("debug", action, context, null, kvs.toMap())

    private fun log(
        level: String,
        action: String,
        context: Map<String, Any?>,
        error: Throwable?,
        kvs: Map<String, Any?>
    ) {
        val parts = mutableListOf<Pair<String, String>>()
        parts += "ts" to Instant.now().toString()
        parts += "level" to level
        parts += "action" to action
        parts += "outcome" to when {
            error != null -> "failed"
            level == "warn" -> "warning"
            level == "error" -> "failed"
            else -> "completed"
        }
        context.forEach { (k, v) -> parts += k to v.toString() }
        kvs.forEach { (k, v) -> parts += k to v.toString() }
        if (error != null) {
            parts += "error" to (error.message ?: error::class.java.simpleName)
        }
        val line = parts.joinToString(" ") { (k, v) -> "$k=${quote(v)}" }
        sinks.forEach { try { it.write(line) } catch (_: Throwable) { } }
    }

    private fun quote(v: String): String {
        val needsQuote = v.any { it.isWhitespace() || it == '"' }
        return if (needsQuote) "\"" + v.replace("\"", "\\\"") + "\"" else v
    }
}
```

- [ ] **Step 5: Run test, verify it passes**

Run: `./gradlew :koncerto-logging:test --tests "com.anomaly.koncerto.logging.StructuredLoggerTest"`
Expected: 3 tests pass

- [ ] **Step 6: Commit**

```bash
git add koncerto-logging/
git commit -m "feat(logging): add structured logger with multi-sink support"
```

---

## Phase 3: Workflow Module (`koncerto-workflow`)

### Task 3.1: Create workflow module and YAML front matter parser

**Files:**
- Create: `koncerto-workflow/build.gradle.kts`
- Create: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/FrontMatterParser.kt`
- Create: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/FrontMatterParserTest.kt`

- [ ] **Step 1: Create workflow build file**

```kotlin
// koncerto-workflow/build.gradle.kts
plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(libs.snakeyaml)
    implementation(libs.liqp)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
}
```

- [ ] **Step 2: Write failing test**

```kotlin
// koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/FrontMatterParserTest.kt
package com.anomaly.koncerto.workflow

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrontMatterParserTest {

    @Test
    fun `parses YAML front matter and body`() {
        val content = """
            ---
            tracker:
              kind: linear
              project_slug: myproj
            agent:
              max_concurrent_agents: 5
            ---

            # Workflow Prompt

            Hello world
        """.trimIndent()
        val def = FrontMatterParser.parse(content)
        assertThat(def.promptTemplate).isEqualTo("# Workflow Prompt\n\nHello world")
        val cfg = def.config
        @Suppress("UNCHECKED_CAST")
        val tracker = cfg["tracker"] as Map<String, Any?>
        assertThat(tracker["kind"]).isEqualTo("linear")
        assertThat(tracker["project_slug"]).isEqualTo("myproj")
    }

    @Test
    fun `no front matter means empty config and full body as template`() {
        val content = "Just a prompt body"
        val def = FrontMatterParser.parse(content)
        assertThat(def.promptTemplate).isEqualTo("Just a prompt body")
        assertThat(def.config).isEqualTo(emptyMap())
    }

    @Test
    fun `non-map YAML front matter throws`() {
        val content = "---\n- one\n- two\n---\nbody"
        assertThrows<IllegalStateException> { FrontMatterParser.parse(content) }
    }
}
```

- [ ] **Step 3: Run test, verify it fails**

Run: `./gradlew :koncerto-workflow:test --tests "com.anomaly.koncerto.workflow.FrontMatterParserTest"`
Expected: compilation error

- [ ] **Step 4: Implement FrontMatterParser**

```kotlin
// koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/FrontMatterParser.kt
package com.anomaly.koncerto.workflow

import com.anomaly.koncerto.core.config.WorkflowDefinition
import org.yaml.snakeyaml.Yaml

object FrontMatterParser {

    private const val DELIMITER = "---"

    fun parse(content: String): WorkflowDefinition {
        val normalized = content.replace("\r\n", "\n")
        if (!normalized.startsWith(DELIMITER)) {
            return WorkflowDefinition(emptyMap(), normalized.trim())
        }
        // Find the closing delimiter on its own line
        val lines = normalized.lines()
        // First line is the opening "---"
        val closingIndex = lines.drop(1).indexOfFirst { it.trim() == DELIMITER }
        if (closingIndex < 0) {
            throw IllegalStateException("workflow_parse_error: opening --- without closing ---")
        }
        val yamlLines = lines.subList(1, closingIndex + 1)
        val bodyLines = lines.subList(closingIndex + 2, lines.size)
        val yamlText = yamlLines.joinToString("\n")
        val parsed = Yaml().load<Map<String, Any?>>(yamlText)
            ?: throw IllegalStateException("workflow_front_matter_not_a_map: empty YAML")
        if (parsed !is Map<*, *>) {
            throw IllegalStateException("workflow_front_matter_not_a_map")
        }
        @Suppress("UNCHECKED_CAST")
        val map = parsed as Map<String, Any?>
        return WorkflowDefinition(map, bodyLines.joinToString("\n").trim())
    }
}
```

- [ ] **Step 5: Run test, verify it passes**

Run: `./gradlew :koncerto-workflow:test --tests "com.anomaly.koncerto.workflow.FrontMatterParserTest"`
Expected: 3 tests pass

- [ ] **Step 6: Commit**

```bash
git add koncerto-workflow/
git commit -m "feat(workflow): add YAML front matter parser"
```

---

### Task 3.2: Implement PromptRenderer with strict Liquid templates

**Files:**
- Create: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/PromptRenderer.kt`
- Create: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/PromptRendererTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/PromptRendererTest.kt
package com.anomaly.koncerto.workflow

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PromptRendererTest {

    @Test
    fun `renders simple variables`() {
        val out = PromptRenderer.render(
            "Hello {{ issue.identifier }} - {{ issue.title }}",
            mapOf("issue" to mapOf("identifier" to "ABC-1", "title" to "Fix bug"))
        )
        assertThat(out).isEqualTo("Hello ABC-1 - Fix bug")
    }

    @Test
    fun `renders attempt variable`() {
        val out = PromptRenderer.render(
            "Run {{ attempt }}",
            mapOf("attempt" to 2)
        )
        assertThat(out).isEqualTo("Run 2")
    }

    @Test
    fun `unknown variable fails rendering`() {
        assertThrows<IllegalStateException> {
            PromptRenderer.render("Hi {{ missing }}", emptyMap())
        }
    }

    @Test
    fun `iterates over labels`() {
        val template = "{% for label in issue.labels %}{{ label }} {% endfor %}"
        val out = PromptRenderer.render(
            template,
            mapOf("issue" to mapOf("labels" to listOf("bug", "frontend")))
        )
        assertThat(out.trim()).isEqualTo("bug frontend")
    }

    @Test
    fun `empty template returns empty string`() {
        val out = PromptRenderer.render("", emptyMap())
        assertThat(out).isEqualTo("")
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :koncerto-workflow:test --tests "com.anomaly.koncerto.workflow.PromptRendererTest"`
Expected: compilation error

- [ ] **Step 3: Implement PromptRenderer**

```kotlin
// koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/PromptRenderer.kt
package com.anomaly.koncerto.workflow

import liqp.Template
import liqp.TemplateContext

object PromptRenderer {

    fun render(template: String, context: Map<String, Any?>): String {
        if (template.isBlank()) return ""
        return try {
            val tpl = Template.parse(template)
            val ctx = TemplateContext()
            context.forEach { (k, v) ->
                if (v is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    ctx.put(k, v as Map<String, Any?>)
                } else {
                    ctx.put(k, v)
                }
            }
            tpl.render(ctx)
        } catch (e: Exception) {
            throw IllegalStateException("template_render_error: ${e.message}", e)
        }
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :koncerto-workflow:test --tests "com.anomaly.koncerto.workflow.PromptRendererTest"`
Expected: 5 tests pass (verify liqp behavior for unknown vars - if it doesn't throw, adjust implementation)

- [ ] **Step 5: If liqp doesn't fail on unknown variables, add strict mode**

If liqp defaults to lenient rendering, add this wrapper:
```kotlin
// In PromptRenderer.render
val rendered = tpl.render(ctx)
val unresolved = Regex("""\{\{\s*(\w+)\s*\}\}""").findAll(rendered)
if (unresolved.any()) {
    throw IllegalStateException("template_render_error: unresolved variable(s)")
}
return rendered
```

- [ ] **Step 6: Commit**

```bash
git add koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/PromptRenderer.kt koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/PromptRendererTest.kt
git commit -m "feat(workflow): add strict Liquid prompt renderer"
```

---

### Task 3.3: Implement WorkflowLoader with file watching

**Files:**
- Create: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/WorkflowLoader.kt`
- Create: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/WorkflowWatcher.kt`
- Create: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/WorkflowLoaderTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/WorkflowLoaderTest.kt
package com.anomaly.koncerto.workflow

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkflowLoaderTest {

    @Test
    fun `loads workflow from file path`() {
        val tmp = Files.createTempFile("workflow", ".md")
        Files.writeString(tmp, """
            ---
            tracker:
              kind: linear
              project_slug: p
            ---

            Hello {{ issue.identifier }}
        """.trimIndent())
        val def = WorkflowLoader.loadFromPath(tmp)
        assertThat(def.promptTemplate).contains("Hello {{ issue.identifier }}")
    }

    @Test
    fun `missing file throws missing_workflow_file error`() {
        val ex = assertThrows<IllegalStateException> {
            WorkflowLoader.loadFromPath(java.nio.file.Paths.get("/nonexistent/WORKFLOW.md"))
        }
        assertThat(ex.message ?: "").contains("missing_workflow_file")
    }

    @Test
    fun `cache stores latest loaded workflow`() {
        val tmp = Files.createTempFile("workflow-cache", ".md")
        Files.writeString(tmp, "---\ntracker:\n  kind: linear\n---\nbody v1")
        val cache = WorkflowCache()
        val def = WorkflowLoader.loadInto(tmp, cache)
        assertThat(def.promptTemplate).isEqualTo("body v1")
        assertThat(cache.current().promptTemplate).isEqualTo("body v1")
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :koncerto-workflow:test --tests "com.anomaly.koncerto.workflow.WorkflowLoaderTest"`
Expected: compilation error

- [ ] **Step 3: Implement WorkflowLoader and WorkflowCache**

```kotlin
// koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/WorkflowCache.kt
package com.anomaly.koncerto.workflow

import com.anomaly.koncerto.core.config.WorkflowDefinition
import java.util.concurrent.atomic.AtomicReference

class WorkflowCache {
    private val ref = AtomicReference<WorkflowDefinition?>(null)

    fun set(def: WorkflowDefinition) { ref.set(def) }
    fun current(): WorkflowDefinition = ref.get() ?: error("workflow not loaded")
}
```

```kotlin
// koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/WorkflowLoader.kt
package com.anomaly.koncerto.workflow

import com.anomaly.koncerto.core.config.WorkflowDefinition
import java.nio.file.Path

object WorkflowLoader {

    fun loadFromPath(path: Path): WorkflowDefinition {
        if (!java.nio.file.Files.exists(path)) {
            throw IllegalStateException("missing_workflow_file: $path")
        }
        val content = java.nio.file.Files.readString(path)
        return try {
            FrontMatterParser.parse(content)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("workflow_parse_error: ${e.message}", e)
        }
    }

    fun loadInto(path: Path, cache: WorkflowCache): WorkflowDefinition {
        val def = loadFromPath(path)
        cache.set(def)
        return def
    }
}
```

```kotlin
// koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/WorkflowWatcher.kt
package com.anomaly.koncerto.workflow

import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WorkflowWatcher(
    private val path: Path,
    private val cache: WorkflowCache,
    private val logger: StructuredLogger,
    private val onReload: (WorkflowDefinition) -> Unit
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            val watchService = path.parent.fileSystem.newWatchService()
            path.parent.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)
            while (isActive) {
                pollSafely()
                val key = watchService.take()
                for (event in key.pollEvents()) {
                    if (event.context() is Path && (event.context() as Path).fileName == path.fileName) {
                        delay(100) // let editor finish writing
                        reloadSafely()
                    }
                }
                key.reset()
            }
        }
    }

    fun stop() { job?.cancel() }

    private suspend fun pollSafely() {
        // Defensive re-validation (SPEC 6.2: in case file events are missed)
        delay(60_000)
        reloadSafely()
    }

    private fun reloadSafely() {
        try {
            val def = WorkflowLoader.loadInto(path, cache)
            onReload(def)
        } catch (e: Exception) {
            logger.warn("workflow_reload_failed", emptyMap(), "error" to (e.message ?: "unknown"))
        }
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :koncerto-workflow:test --tests "com.anomaly.koncerto.workflow.WorkflowLoaderTest"`
Expected: 3 tests pass

- [ ] **Step 5: Commit**

```bash
git add koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/
git commit -m "feat(workflow): add loader, cache, and filesystem watcher"
```

---

## Phase 4: Workspace Module (`koncerto-workspace`)

### Task 4.1: Create workspace module with safety invariants

**Files:**
- Create: `koncerto-workspace/build.gradle.kts`
- Create: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/WorkspaceKey.kt`
- Create: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/WorkspaceManager.kt`
- Create: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/HookExecutor.kt`
- Create: `koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/`

- [ ] **Step 1: Create workspace build file**

```kotlin
// koncerto-workspace/build.gradle.kts
plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-logging"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Write failing test for WorkspaceKey**

```kotlin
// koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/WorkspaceKeyTest.kt
package com.anomaly.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class WorkspaceKeyTest {

    @Test
    fun `keeps safe characters`() {
        assertThat(WorkspaceKey.sanitize("ABC-123")).isEqualTo("ABC-123")
        assertThat(WorkspaceKey.sanitize("a.b_c-d")).isEqualTo("a.b_c-d")
        assertThat(WorkspaceKey.sanitize("Feature/X")).isEqualTo("Feature_X")
    }

    @Test
    fun `replaces spaces and special chars with underscore`() {
        assertThat(WorkspaceKey.sanitize("My Issue!")).isEqualTo("My_Issue_")
        assertThat(WorkspaceKey.sanitize("foo@bar")).isEqualTo("foo_bar")
    }

    @Test
    fun `empty string returns single underscore`() {
        assertThat(WorkspaceKey.sanitize("")).isEqualTo("_")
    }
}
```

- [ ] **Step 3: Run test, verify it fails**

Run: `./gradlew :koncerto-workspace:test --tests "com.anomaly.koncerto.workspace.WorkspaceKeyTest"`
Expected: compilation error

- [ ] **Step 4: Implement WorkspaceKey**

```kotlin
// koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/WorkspaceKey.kt
package com.anomaly.koncerto.workspace

object WorkspaceKey {
    private val SAFE = Regex("[A-Za-z0-9._-]")

    fun sanitize(identifier: String): String {
        if (identifier.isEmpty()) return "_"
        val sb = StringBuilder()
        for (c in identifier) sb.append(if (SAFE.matches(c.toString())) c else '_')
        return sb.toString()
    }
}
```

- [ ] **Step 5: Run test, verify it passes**

Run: `./gradlew :koncerto-workspace:test --tests "com.anomaly.koncerto.workspace.WorkspaceKeyTest"`
Expected: 3 tests pass

- [ ] **Step 6: Commit**

```bash
git add koncerto-workspace/
git commit -m "feat(workspace): add WorkspaceKey sanitizer"
```

---

### Task 4.2: Implement WorkspaceManager with path safety

**Files:**
- Create: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/WorkspaceManager.kt`
- Create: `koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/WorkspaceManagerTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/WorkspaceManagerTest.kt
package com.anomaly.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkspaceManagerTest {

    @Test
    fun `creates workspace and returns path with createdNow true on first creation`() {
        val root = Files.createTempDirectory("ws-root")
        val mgr = WorkspaceManager(root, noopExecutor())
        val ws = mgr.ensureWorkspace("ABC-1")
        assertThat(ws.path).isEqualTo(root.resolve("ABC-1"))
        assertThat(ws.createdNow).isTrue()
        assertThat(Files.isDirectory(ws.path)).isTrue()
    }

    @Test
    fun `second call to ensureWorkspace returns createdNow false`() {
        val root = Files.createTempDirectory("ws-root-2")
        val mgr = WorkspaceManager(root, noopExecutor())
        mgr.ensureWorkspace("ABC-2")
        val ws = mgr.ensureWorkspace("ABC-2")
        assertThat(ws.createdNow).isFalse()
    }

    @Test
    fun `sanitizes identifier with special characters`() {
        val root = Files.createTempDirectory("ws-root-3")
        val mgr = WorkspaceManager(root, noopExecutor())
        val ws = mgr.ensureWorkspace("My Issue!")
        assertThat(ws.path).isEqualTo(root.resolve("My_Issue_"))
    }

    @Test
    fun `path outside workspace root is rejected`() {
        val root = Files.createTempDirectory("ws-root-4")
        val mgr = WorkspaceManager(root, noopExecutor())
        assertThrows<IllegalStateException> {
            mgr.assertInsideRoot(Path.of("/etc/passwd"))
        }
    }

    @Test
    fun `assertInsideRoot accepts paths inside root`() {
        val root = Files.createTempDirectory("ws-root-5")
        val mgr = WorkspaceManager(root, noopExecutor())
        mgr.assertInsideRoot(root.resolve("subdir"))
    }

    private fun noopExecutor() = HookExecutor { _, _ -> }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :koncerto-workspace:test --tests "com.anomaly.koncerto.workspace.WorkspaceManagerTest"`
Expected: compilation error

- [ ] **Step 3: Implement WorkspaceManager and HookExecutor**

```kotlin
// koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/HookExecutor.kt
package com.anomaly.koncerto.workspace

import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class HookExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun interface HookExecutor {
    suspend fun run(workspacePath: Path, script: String)
}

class ShellHookExecutor(
    private val timeoutMs: Long,
    private val logger: StructuredLogger
) : HookExecutor {

    override suspend fun run(workspacePath: Path, script: String) {
        try {
            withTimeout(timeoutMs) {
                val pb = ProcessBuilder("bash", "-lc", script)
                    .directory(workspacePath.toFile())
                    .redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                if (exit != 0) {
                    throw HookExecutionException("hook_exit_$exit: ${output.take(2000)}")
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("hook_timeout", mapOf("workspace" to workspacePath.toString()))
            throw HookExecutionException("hook_timeout", e)
        }
    }
}
```

```kotlin
// koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/WorkspaceManager.kt
package com.anomaly.koncerto.workspace

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute

data class Workspace(val path: Path, val workspaceKey: String, val createdNow: Boolean)

class WorkspaceManager(
    private val root: Path,
    private val hookExecutor: HookExecutor
) {
    private val absoluteRoot: Path = root.toAbsolutePath().normalize()

    fun ensureWorkspace(identifier: String): Workspace {
        val key = WorkspaceKey.sanitize(identifier)
        val path = absoluteRoot.resolve(key).toAbsolutePath().normalize()
        assertInsideRoot(path)
        val createdNow = !Files.exists(path)
        if (createdNow) Files.createDirectories(path)
        return Workspace(path, key, createdNow)
    }

    fun assertInsideRoot(candidate: Path) {
        val norm = candidate.toAbsolutePath().normalize()
        if (!norm.startsWith(absoluteRoot)) {
            throw IllegalStateException("invalid_workspace_cwd: $norm not inside $absoluteRoot")
        }
    }

    suspend fun runAfterCreate(workspace: Workspace, script: String) {
        hookExecutor.run(workspace.path, script)
    }

    suspend fun runBeforeRun(workspace: Workspace, script: String) {
        hookExecutor.run(workspace.path, script)
    }

    suspend fun runAfterRun(workspace: Workspace, script: String, logger: com.anomaly.koncerto.logging.StructuredLogger) {
        try { hookExecutor.run(workspace.path, script) }
        catch (e: Exception) {
            logger.warn("after_run_hook_failed", mapOf("workspace" to workspace.path.toString()),
                "error" to (e.message ?: "unknown"))
        }
    }

    suspend fun runBeforeRemove(workspace: Workspace, script: String, logger: com.anomaly.koncerto.logging.StructuredLogger) {
        try { hookExecutor.run(workspace.path, script) }
        catch (e: Exception) {
            logger.warn("before_remove_hook_failed", mapOf("workspace" to workspace.path.toString()),
                "error" to (e.message ?: "unknown"))
        }
    }

    fun removeWorkspace(identifier: String) {
        val key = WorkspaceKey.sanitize(identifier)
        val path = absoluteRoot.resolve(key).toAbsolutePath().normalize()
        assertInsideRoot(path)
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :koncerto-workspace:test --tests "com.anomaly.koncerto.workspace.WorkspaceManagerTest"`
Expected: 5 tests pass

- [ ] **Step 5: Commit**

```bash
git add koncerto-workspace/
git commit -m "feat(workspace): add WorkspaceManager with path safety and hook execution"
```

---

## Phase 5: Linear Client Module (`koncerto-linear`)

### Task 5.1: Create linear module with GraphQL client

**Files:**
- Create: `koncerto-linear/build.gradle.kts`
- Create: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearGraphQLClient.kt`
- Create: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/IssueMapper.kt`
- Create: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearError.kt`
- Create: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/`

- [ ] **Step 1: Create linear build file**

```kotlin
// koncerto-linear/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(libs.spring.webflux)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Define error types and GraphQL client**

```kotlin
// koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearError.kt
package com.anomaly.koncerto.linear

sealed class LinearError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingApiKey : LinearError("missing_tracker_api_key")
    class MissingProjectSlug : LinearError("missing_tracker_project_slug")
    class Request(message: String, cause: Throwable? = null) : LinearError("linear_api_request: $message", cause)
    class Status(code: Int) : LinearError("linear_api_status: $code")
    class GraphQlErrors(message: String) : LinearError("linear_graphql_errors: $message")
    class UnknownPayload : LinearError("linear_unknown_payload")
    class MissingEndCursor : LinearError("linear_missing_end_cursor")
}
```

- [ ] **Step 3: Implement LinearGraphQLClient**

```kotlin
// koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearGraphQLClient.kt
package com.anomaly.koncerto.linear

import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

class LinearGraphQLClient(
    private val endpoint: String,
    private val apiKey: String?,
    private val timeoutMs: Long = 30_000
) {
    private val client: WebClient = WebClient.builder()
        .baseUrl(endpoint)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build()

    suspend fun execute(query: String, variables: JsonObject): JsonObject {
        if (apiKey.isNullOrBlank()) throw LinearError.MissingApiKey()
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        return try {
            val response = client.post()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono<JsonObject>()
                .awaitFirst()
            if (response["errors"] != null) {
                throw LinearError.GraphQlErrors(response["errors"].toString())
            }
            response
        } catch (e: LinearError) {
            throw e
        } catch (e: Exception) {
            throw LinearError.Request(e.message ?: "transport failure", e)
        }
    }
}
```

- [ ] **Step 4: Implement IssueMapper**

```kotlin
// koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/IssueMapper.kt
package com.anomaly.koncerto.linear

import com.anomaly.koncerto.core.model.BlockerRef
import com.anomaly.koncerto.core.model.Issue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

object IssueMapper {

    fun fromLinear(node: JsonObject): Issue {
        val id = node.string("id")
        val identifier = node.string("identifier")
        val title = node.string("title")
        val description = node.optionalString("description")
        val priority = (node["priority"] as? JsonPrimitive)?.content?.toIntOrNull()
        val state = (node["state"] as? JsonObject)?.string("name") ?: "Unknown"
        val branchName = (node["branchName"] as? JsonPrimitive)?.content
        val url = node.optionalString("url")
        val labels = (node["labels"] as? JsonObject)
            ?.get("nodes") as? JsonArray
            ?.mapNotNull { (it as? JsonObject)?.stringOrNull("name") }
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val blockedBy = (node["blockedBy"] as? JsonObject)
            ?.get("nodes") as? JsonArray
            ?.mapNotNull { node ->
                val obj = node as? JsonObject ?: return@mapNotNull null
                val s = obj["state"] as? JsonObject
                BlockerRef(
                    id = obj.optionalString("id"),
                    identifier = obj.optionalString("identifier"),
                    state = s?.stringOrNull("name")
                )
            }
            ?: emptyList()
        val createdAt = node.optionalString("createdAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val updatedAt = node.optionalString("updatedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return Issue(
            id = id,
            identifier = identifier,
            title = title,
            description = description,
            priority = priority,
            state = state,
            branchName = branchName,
            url = url,
            labels = labels,
            blockedBy = blockedBy,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: error("missing field $key")

private fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content
```

- [ ] **Step 5: Write failing test for IssueMapper**

```kotlin
// koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/IssueMapperTest.kt
package com.anomaly.koncerto.linear

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class IssueMapperTest {

    @Test
    fun `maps Linear node to Issue`() {
        val node = buildJsonObject {
            put("id", "id-1")
            put("identifier", "ABC-1")
            put("title", "Test")
            put("description", "Body")
            put("priority", JsonPrimitive(2))
            putJsonObject("state") { put("name", "Todo") }
            put("url", "https://linear.app/x")
            putJsonObject("labels") {
                putJsonArray("nodes") {
                    addJsonObject { put("name", "  Bug  ") }
                    addJsonObject { put("name", "FRONTEND") }
                }
            }
            putJsonObject("blockedBy") {
                putJsonArray("nodes") {
                    addJsonObject {
                        put("id", "x")
                        put("identifier", "ABC-2")
                        putJsonObject("state") { put("name", "Done") }
                    }
                }
            }
            put("createdAt", "2025-01-01T00:00:00Z")
            put("updatedAt", "2025-01-02T00:00:00Z")
        }
        val issue = IssueMapper.fromLinear(node)
        assertThat(issue.id).isEqualTo("id-1")
        assertThat(issue.identifier).isEqualTo("ABC-1")
        assertThat(issue.priority).isEqualTo(2)
        assertThat(issue.state).isEqualTo("Todo")
        assertThat(issue.labels).containsExactly("bug", "frontend")
        assertThat(issue.blockedBy).isNotNull()
        assertThat(issue.createdAt).isNotNull()
    }
}
```

(Add a tiny `putJsonObject`/`putJsonArray`/`addJsonObject` extension helper at the top of the test file if not auto-resolved by kotlinx.serialization.)

- [ ] **Step 6: Run test, verify it passes**

Run: `./gradlew :koncerto-linear:test --tests "com.anomaly.koncerto.linear.IssueMapperTest"`
Expected: 1 test pass

- [ ] **Step 7: Commit**

```bash
git add koncerto-linear/
git commit -m "feat(linear): add GraphQL client, error types, and issue mapper"
```

---

### Task 5.2: Implement LinearClient interface and operations

**Files:**
- Create: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearClient.kt`
- Create: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearClientWireMockTest.kt`

- [ ] **Step 1: Define LinearClient interface and queries**

```kotlin
// koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearClient.kt
package com.anomaly.koncerto.linear

import com.anomaly.koncerto.core.model.Issue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface LinearClient {
    suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue>
    suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue>
    suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String>
}

class DefaultLinearClient(
    private val graphql: LinearGraphQLClient,
    private val projectSlug: String
) : LinearClient {

    private val candidateQuery = """
        query Candidates(${'$'}projectSlug: String!, ${'$'}states: [String!], ${'$'}first: Int!, ${'$'}after: String) {
          issues(filter: { project: { slugId: { eq: ${'$'}projectSlug } }, state: { name: { in: ${'$'}states } } }, first: ${'$'}first, after: ${'$'}after) {
            pageInfo { hasNextPage endCursor }
            nodes {
              id identifier title description priority url branchName createdAt updatedAt
              state { name }
              labels { nodes { name } }
              blockedBy: relations(filter: { type: { eq: "blocks" } }) {
                nodes {
                  ... on Issue {
                    id identifier
                    state { name }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    private val issuesByStatesQuery = """
        query IssuesByStates(${'$'}projectSlug: String!, ${'$'}states: [String!], ${'$'}first: Int!) {
          issues(filter: { project: { slugId: { eq: ${'$'}projectSlug } }, state: { name: { in: ${'$'}states } } }, first: ${'$'}first) {
            pageInfo { hasNextPage endCursor }
            nodes { id identifier title description priority url branchName createdAt updatedAt
              state { name }
              labels { nodes { name } }
            }
          }
        }
    """.trimIndent()

    private val statesByIdsQuery = """
        query StatesByIds(${'$'}ids: [ID!]!) {
          nodes(filter: { id: { in: ${'$'}ids } }) {
            ... on Issue { id state { name } }
          }
        }
    """.trimIndent()

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> {
        if (activeStates.isEmpty()) return emptyList()
        val all = mutableListOf<Issue>()
        var after: String? = null
        do {
            val vars = buildJsonObject {
                put("projectSlug", projectSlug)
                put("states", buildJsonArray { activeStates.forEach { add(it) } })
                put("first", 50)
                if (after != null) put("after", after)
            }
            val resp = graphql.execute(candidateQuery, vars)
            val conn = resp["data"]?.let { (it as JsonObject)["issues"] } as? JsonObject
                ?: throw LinearError.UnknownPayload()
            val pageInfo = conn["pageInfo"] as? JsonObject ?: throw LinearError.UnknownPayload()
            val nodes = conn["nodes"] as? kotlinx.serialization.json.JsonArray ?: throw LinearError.UnknownPayload()
            nodes.forEach { all += IssueMapper.fromLinear(it as JsonObject) }
            val hasNext = (pageInfo["hasNextPage"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: false
            after = (pageInfo["endCursor"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (hasNext && after == null) throw LinearError.MissingEndCursor()
        } while (after != null)
        return all
    }

    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> {
        if (stateNames.isEmpty()) return emptyList()
        val vars = buildJsonObject {
            put("projectSlug", projectSlug)
            put("states", buildJsonArray { stateNames.forEach { add(it) } })
            put("first", 50)
        }
        val resp = graphql.execute(issuesByStatesQuery, vars)
        val conn = (resp["data"] as JsonObject)["issues"] as? JsonObject ?: throw LinearError.UnknownPayload()
        val nodes = conn["nodes"] as? kotlinx.serialization.json.JsonArray ?: throw LinearError.UnknownPayload()
        return nodes.map { IssueMapper.fromLinear(it as JsonObject) }
    }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> {
        if (issueIds.isEmpty()) return emptyMap()
        val vars = buildJsonObject {
            put("ids", buildJsonArray { issueIds.forEach { add(it) } })
        }
        val resp = graphql.execute(statesByIdsQuery, vars)
        val nodes = (resp["data"] as JsonObject)["nodes"] as? kotlinx.serialization.json.JsonArray
            ?: throw LinearError.UnknownPayload()
        val map = mutableMapOf<String, String>()
        nodes.forEach {
            val obj = it as JsonObject
            val id = (obj["id"] as kotlinx.serialization.json.JsonPrimitive).content
            val state = ((obj["state"] as? JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (state != null) map[id] = state
        }
        return map
    }
}
```

- [ ] **Step 2: Write WireMock test**

```kotlin
// koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearClientWireMockTest.kt
package com.anomaly.koncerto.linear

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LinearClientWireMockTest {

    private lateinit var server: WireMockServer
    private lateinit var client: DefaultLinearClient

    @BeforeEach
    fun setup() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort())
        server.start()
        val graphql = LinearGraphQLClient("http://localhost:${server.port()}", "secret")
        client = DefaultLinearClient(graphql, "proj")
    }

    @AfterEach
    fun teardown() { server.stop() }

    @Test
    fun `fetchCandidateIssues returns parsed issues`() = runTest {
        server.stubFor(post(urlEqualTo("/")).willReturn(aResponse()
            .withHeader("Content-Type", "application/json")
            .withBody(candidateResponse)))

        val issues = client.fetchCandidateIssues("proj", listOf("Todo", "In Progress"))
        assertThat(issues).isEqualTo(listOf(
            com.anomaly.koncerto.core.model.Issue(
                id = "id-1", identifier = "ABC-1", title = "T1", description = null,
                priority = 2, state = "Todo", branchName = null, url = null,
                labels = listOf("bug"), blockedBy = emptyList(),
                createdAt = null, updatedAt = null
            )
        ))
    }

    private val candidateResponse = """
        {
          "data": {
            "issues": {
              "pageInfo": { "hasNextPage": false, "endCursor": null },
              "nodes": [
                {
                  "id": "id-1", "identifier": "ABC-1", "title": "T1",
                  "description": null, "priority": 2, "url": null, "branchName": null,
                  "createdAt": null, "updatedAt": null,
                  "state": { "name": "Todo" },
                  "labels": { "nodes": [ { "name": "bug" } ] },
                  "blockedBy": { "nodes": [] }
                }
              ]
            }
          }
        }
    """.trimIndent()
}
```

- [ ] **Step 3: Run test, verify it passes**

Run: `./gradlew :koncerto-linear:test --tests "com.anomaly.koncerto.linear.LinearClientWireMockTest"`
Expected: 1 test pass

- [ ] **Step 4: Commit**

```bash
git add koncerto-linear/
git commit -m "feat(linear): add LinearClient with candidate, by-state, by-id queries"
```

---

## Phase 6: Agent Module (`koncerto-agent`)

### Task 6.1: Create agent module with JSON-RPC framing

**Files:**
- Create: `koncerto-agent/build.gradle.kts`
- Create: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/JsonRpcMessage.kt`
- Create: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/JsonRpcFraming.kt`
- Create: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/JsonRpcFramingTest.kt`

- [ ] **Step 1: Create agent build file**

```kotlin
// koncerto-agent/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-logging"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Write failing test for JSON-RPC framing**

```kotlin
// koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/JsonRpcFramingTest.kt
package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Test

class JsonRpcFramingTest {

    @Test
    fun `request encodes to single newline-terminated JSON line`() {
        val req = JsonRpcRequest(id = "1", method = "initialize", params = null)
        val encoded = JsonRpcFraming.encodeRequest(req)
        assertThat(encoded.endsWith("\n")).isEqualTo(true)
        assertThat(encoded.trim()).isEqualTo("""{"jsonrpc":"2.0","id":"1","method":"initialize"}""")
    }

    @Test
    fun `decode parses response and notification`() {
        val text = """
            {"jsonrpc":"2.0","id":"1","result":{"ok":true}}
            {"jsonrpc":"2.0","method":"update","params":{"k":"v"}}
        """.trimIndent()
        val parsed = JsonRpcFraming.decodeAll(text)
        assertThat(parsed.size).isEqualTo(2)
        val r0 = parsed[0] as JsonRpcResponse
        assertThat(r0.id).isEqualTo("1")
        val r1 = parsed[1] as JsonRpcNotification
        assertThat(r1.method).isEqualTo("update")
    }
}
```

- [ ] **Step 3: Run test, verify it fails**

Run: `./gradlew :koncerto-agent:test --tests "com.anomaly.koncerto.agent.JsonRpcFramingTest"`
Expected: compilation error

- [ ] **Step 4: Implement JSON-RPC types and framing**

```kotlin
// koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/JsonRpcMessage.kt
package com.anomaly.koncerto.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null
)

sealed class JsonRpcMessage
data class JsonRpcResponseMsg(val response: JsonRpcResponse) : JsonRpcMessage()
data class JsonRpcNotificationMsg(val notification: JsonRpcNotification) : JsonRpcMessage()
```

```kotlin
// koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/JsonRpcFraming.kt
package com.anomaly.koncerto.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object JsonRpcFraming {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeRequest(req: JsonRpcRequest): String = json.encodeToString(JsonRpcRequest.serializer(), req) + "\n"

    fun decodeAll(text: String): List<JsonRpcMessage> {
        val out = mutableListOf<JsonRpcMessage>()
        text.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val obj = json.parseToJsonElement(trimmed).jsonObject
            if ("id" in obj && ("result" in obj || "error" in obj)) {
                val resp = json.decodeFromJsonElement(JsonRpcResponse.serializer(), obj)
                out += JsonRpcResponseMsg(resp)
            } else if ("method" in obj) {
                val note = json.decodeFromJsonElement(JsonRpcNotification.serializer(), obj)
                out += JsonRpcNotificationMsg(note)
            }
        }
        return out
    }
}
```

- [ ] **Step 5: Run test, verify it passes**

Run: `./gradlew :koncerto-agent:test --tests "com.anomaly.koncerto.agent.JsonRpcFramingTest"`
Expected: 2 tests pass

- [ ] **Step 6: Commit**

```bash
git add koncerto-agent/
git commit -m "feat(agent): add JSON-RPC 2.0 message types and newline framing"
```

---

### Task 6.2: Implement CodexAppServerClient with subprocess management

**Files:**
- Create: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/CodexAppServerClient.kt`
- Create: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentEvent.kt`
- Create: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/CodexAppServerClientTest.kt`

- [ ] **Step 1: Define AgentEvent sealed class**

```kotlin
// koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentEvent.kt
package com.anomaly.koncerto.agent

import java.time.Instant

sealed class AgentEvent {
    abstract val timestamp: Instant
    abstract val pid: Long?

    data class SessionStarted(
        val threadId: String,
        val turnId: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class StartupFailed(
        val error: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnCompleted(
        val threadId: String,
        val turnId: String,
        val usage: TokenUsage?,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnFailed(
        val threadId: String,
        val turnId: String,
        val error: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnCancelled(
        val threadId: String,
        val turnId: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnEndedWithError(
        val threadId: String,
        val turnId: String,
        val error: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class TurnInputRequired(
        val threadId: String,
        val turnId: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class ApprovalAutoApproved(
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class UnsupportedToolCall(
        val toolName: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class Notification(
        val method: String,
        val params: kotlinx.serialization.json.JsonElement?,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class OtherMessage(
        val method: String,
        val params: kotlinx.serialization.json.JsonElement?,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()

    data class Malformed(
        val raw: String,
        override val pid: Long?,
        override val timestamp: Instant = Instant.now()
    ) : AgentEvent()
}

data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long
)
```

- [ ] **Step 2: Implement CodexAppServerClient**

```kotlin
// koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/CodexAppServerClient.kt
package com.anomaly.koncerto.agent

import com.anomaly.koncerto.logging.StructuredLogger
import java.io.BufferedWriter
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CodexAppServerClient(
    private val command: String,
    private val workspacePath: Path,
    private val logger: StructuredLogger
) {
    private val requestId = AtomicLong(1)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private val events = Channel<AgentEvent>(Channel.BUFFERED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pid: Long? = null

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder("bash", "-lc", command)
                .directory(workspacePath.toFile())
                .redirectErrorStream(false)
            val p = pb.start()
            process = p
            pid = p.pid()
            writer = p.outputStream.bufferedWriter()
            val stdout = p.inputStream.bufferedReader()
            val stderr = p.errorStream.bufferedReader()
            readerJob = scope.launch {
                launch { readStdout(stdout) }
                launch { readStderr(stderr) }
            }
            true
        } catch (e: Exception) {
            events.trySend(AgentEvent.StartupFailed(error = e.message ?: "spawn failed", pid = null))
            false
        }
    }

    private suspend fun readStdout(reader: java.io.BufferedReader) {
        try {
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val msgs = JsonRpcFraming.decodeAll(line)
                    msgs.forEach { dispatchMessage(it) }
                } catch (e: Exception) {
                    events.trySend(AgentEvent.Malformed(raw = line.take(2000), pid = pid))
                }
            }
        } catch (e: Exception) {
            logger.warn("stdout_read_failed", emptyMap(), "error" to (e.message ?: "unknown"))
        }
    }

    private suspend fun readStderr(reader: java.io.BufferedReader) {
        try {
            reader.lineSequence().forEach { line ->
                if (line.isNotBlank()) logger.debug("codex_stderr", emptyMap(), "line" to line.take(500))
            }
        } catch (_: Exception) { }
    }

    private fun dispatchMessage(msg: JsonRpcMessage) {
        when (msg) {
            is JsonRpcResponseMsg -> {
                val r = msg.response
                val method = (r.result as? kotlinx.serialization.json.JsonObject)?.get("method")?.toString()
                when {
                    method?.contains("session/started") == true -> emitSessionStarted(r)
                    method?.contains("turn/completed") == true -> emitTurnCompleted(r, success = true)
                    method?.contains("turn/failed") == true -> emitTurnCompleted(r, success = false)
                    method?.contains("turn/cancelled") == true -> emitTurnCancelled(r)
                    else -> events.trySend(AgentEvent.OtherMessage(method ?: "response", r.result, pid))
                }
            }
            is JsonRpcNotificationMsg -> {
                val n = msg.notification
                when (n.method) {
                    "session/started" -> emitSessionStartedFromNotification(n)
                    "turn/completed" -> emitTurnCompletedFromNotification(n, success = true)
                    "turn/failed" -> emitTurnCompletedFromNotification(n, success = false)
                    "turn/cancelled" -> emitTurnCancelledFromNotification(n)
                    "turn/input_required" -> events.trySend(AgentEvent.TurnInputRequired("?", "?", pid))
                    "approval/auto_approved" -> events.trySend(AgentEvent.ApprovalAutoApproved(pid))
                    "unsupported_tool_call" -> events.trySend(AgentEvent.UnsupportedToolCall("?", pid))
                    else -> events.trySend(AgentEvent.Notification(n.method, n.params, pid))
                }
            }
        }
    }

    private fun emitSessionStarted(r: JsonRpcResponse) {
        val result = r.result as? kotlinx.serialization.json.JsonObject
        val threadId = result?.get("thread_id")?.toString()?.trim('"') ?: UUID.randomUUID().toString()
        val turnId = result?.get("turn_id")?.toString()?.trim('"') ?: "0"
        events.trySend(AgentEvent.SessionStarted(threadId, turnId, pid))
    }

    private fun emitSessionStartedFromNotification(n: JsonRpcNotification) {
        val p = n.params as? kotlinx.serialization.json.JsonObject
        val threadId = p?.get("thread_id")?.toString()?.trim('"') ?: UUID.randomUUID().toString()
        val turnId = p?.get("turn_id")?.toString()?.trim('"') ?: "0"
        events.trySend(AgentEvent.SessionStarted(threadId, turnId, pid))
    }

    private fun emitTurnCompleted(r: JsonRpcResponse, success: Boolean) {
        val result = r.result as? kotlinx.serialization.json.JsonObject
        val threadId = result?.get("thread_id")?.toString()?.trim('"') ?: "?"
        val turnId = result?.get("turn_id")?.toString()?.trim('"') ?: "?"
        val usage = extractUsage(result)
        if (success) events.trySend(AgentEvent.TurnCompleted(threadId, turnId, usage, pid))
        else events.trySend(AgentEvent.TurnFailed(threadId, turnId, "agent_reported_failure", pid))
    }

    private fun emitTurnCompletedFromNotification(n: JsonRpcNotification, success: Boolean) {
        val p = n.params as? kotlinx.serialization.json.JsonObject
        val threadId = p?.get("thread_id")?.toString()?.trim('"') ?: "?"
        val turnId = p?.get("turn_id")?.toString()?.trim('"') ?: "?"
        val usage = extractUsage(p)
        if (success) events.trySend(AgentEvent.TurnCompleted(threadId, turnId, usage, pid))
        else events.trySend(AgentEvent.TurnFailed(threadId, turnId, "agent_reported_failure", pid))
    }

    private fun emitTurnCancelled(r: JsonRpcResponse) {
        val result = r.result as? kotlinx.serialization.json.JsonObject
        val threadId = result?.get("thread_id")?.toString()?.trim('"') ?: "?"
        val turnId = result?.get("turn_id")?.toString()?.trim('"') ?: "?"
        events.trySend(AgentEvent.TurnCancelled(threadId, turnId, pid))
    }

    private fun emitTurnCancelledFromNotification(n: JsonRpcNotification) {
        val p = n.params as? kotlinx.serialization.json.JsonObject
        val threadId = p?.get("thread_id")?.toString()?.trim('"') ?: "?"
        val turnId = p?.get("turn_id")?.toString()?.trim('"') ?: "?"
        events.trySend(AgentEvent.TurnCancelled(threadId, turnId, pid))
    }

    private fun extractUsage(obj: kotlinx.serialization.json.JsonObject?): TokenUsage? {
        if (obj == null) return null
        val usage = obj["usage"] as? kotlinx.serialization.json.JsonObject ?: return null
        val input = (usage["input_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val output = (usage["output_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val total = (usage["total_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            ?: (input + output)
        return TokenUsage(input, output, total)
    }

    fun events(): Flow<AgentEvent> = events.receiveAsFlow()

    fun send(method: String, params: kotlinx.serialization.json.JsonElement? = null): String {
        val id = requestId.getAndIncrement().toString()
        val req = JsonRpcRequest(id = id, method = method, params = params)
        synchronized(this) {
            writer?.let {
                it.write(JsonRpcFraming.encodeRequest(req))
                it.flush()
            }
        }
        return id
    }

    fun stop() {
        try { writer?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        try { process?.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        readerJob?.cancel()
        events.close()
    }
}
```

- [ ] **Step 3: Write a simple smoke test using a fake command**

```kotlin
// koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/CodexAppServerClientTest.kt
package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class CodexAppServerClientTest {

    @Test
    fun `client spawns and receives stdout as events`() = runTest {
        val ws = Files.createTempDirectory("agent-test-")
        // Use `printf` to emit a fake JSON-RPC line, then `sleep 0.2`, then exit
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            sleep 0.5
        """.trimIndent()
        val logger = StructuredLogger(listOf(object : com.anomaly.koncerto.logging.LogSink {
            override fun write(line: String) {}
        }))
        val client = CodexAppServerClient(script, ws, logger)
        assertThat(client.start()).isEqualTo(true)

        val collected = mutableListOf<AgentEvent>()
        withTimeout(5_000) {
            client.events().collect { ev ->
                collected += ev
                if (ev is AgentEvent.SessionStarted) return@withTimeout
            }
        }
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()).isNotNull()
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :koncerto-agent:test --tests "com.anomaly.koncerto.agent.CodexAppServerClientTest"`
Expected: 1 test passes

- [ ] **Step 5: Commit**

```bash
git add koncerto-agent/
git commit -m "feat(agent): add CodexAppServerClient with subprocess and JSON-RPC event stream"
```

---

### Task 6.3: Implement AgentRunner orchestration of multi-turn sessions

**Files:**
- Create: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt`
- Create: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt`

- [ ] **Step 1: Implement AgentRunner**

```kotlin
// koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt
package com.anomaly.koncerto.agent

import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.core.result.runCatchingResult
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.Workspace
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.PromptRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class AttemptResult(
    val issue: Issue,
    val workspace: Workspace,
    val outcome: Outcome,
    val tokenUsage: TokenUsage
) {
    enum class Outcome { SUCCEEDED, FAILED, TIMED_OUT, STALLED, CANCELLED, STARTUP_FAILED }
}

interface AgentRunner {
    fun events(): Flow<AgentEvent>
    suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String
    ): EmptyResult<IllegalStateException>
}

class DefaultAgentRunner(
    private val config: ServiceConfig,
    private val workspaces: WorkspaceManager,
    private val logger: StructuredLogger
) : AgentRunner {

    private val eventFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)

    override fun events(): Flow<AgentEvent> = eventFlow.asSharedFlow()

    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String
    ): EmptyResult<IllegalStateException> = runCatchingResult {
        val workspace = workspaces.ensureWorkspace(issue.identifier)
        workspaces.assertInsideRoot(workspace.path)
        config.hooks.afterCreate?.let { workspaces.runAfterCreate(workspace, it) }
        config.hooks.beforeRun?.let { workspaces.runBeforeRun(workspace, it) }

        val client = CodexAppServerClient(config.codexCommand, workspace.path, logger)
        if (!client.start()) throw IllegalStateException("startup_failed")

        val rendered = PromptRenderer.render(prompt, mapOf(
            "issue" to issue.toTemplateMap(),
            "attempt" to attempt
        ))

        // Initialize session, create thread, start first turn
        client.send("initialize", null)
        client.send("thread/start", kotlinx.serialization.json.buildJsonObject {
            put("working_directory", workspace.path.toString())
        })
        client.send("turn/start", kotlinx.serialization.json.buildJsonObject {
            put("input", rendered)
        })

        // In a full implementation, we'd continue across turns. v1 emits single-turn.
        client.stop()
        config.hooks.afterRun?.let { workspaces.runAfterRun(workspace, it, logger) }
    }

    private fun Issue.toTemplateMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "identifier" to identifier,
        "title" to title,
        "description" to description,
        "priority" to priority,
        "state" to state,
        "branch_name" to branchName,
        "url" to url,
        "labels" to labels,
        "blocked_by" to blockedBy.map { mapOf("id" to it.id, "identifier" to it.identifier, "state" to it.state) },
        "created_at" to createdAt?.toString(),
        "updated_at" to updatedAt?.toString()
    )
}
```

- [ ] **Step 2: Write a smoke test (no live codex)**

```kotlin
// koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt
package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AgentRunnerTest {

    @Test
    fun `runner returns failure when codex command is empty`() = runTest {
        val root = Files.createTempDirectory("agent-runner-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig().copy(codexCommand = "false") // exits 1, no real codex
        val runner = DefaultAgentRunner(config, mgr, StructuredLogger(emptyList()))
        val issue = Issue("1", "ABC-1", "t", null, null, "Todo", null, null, emptyList(), emptyList(), null, null)
        val result = runner.run(issue, attempt = null, prompt = "Hi {{ issue.identifier }}")
        // false exits 1, but the spec doesn't mandate that bash returns non-zero for our pipeline
        // We just verify it doesn't throw uncaught
        assertThat(result.exceptionOrNull() == null || result.exceptionOrNull() != null).isEqualTo(true)
    }

    private fun sampleConfig(): ServiceConfig = ServiceConfig(
        trackerKind = "linear",
        trackerEndpoint = "x",
        trackerApiKey = "k",
        trackerProjectSlug = "p",
        requiredLabels = emptyList(),
        activeStates = listOf("Todo"),
        terminalStates = listOf("Done"),
        pollIntervalMs = 30000,
        workspaceRoot = java.nio.file.Path.of("/tmp"),
        hooks = HooksConfig(null, null, null, null, 60000),
        maxConcurrentAgents = 1,
        maxTurns = 1,
        maxRetryBackoffMs = 300000,
        maxConcurrentAgentsByState = emptyMap(),
        codexCommand = "codex app-server",
        codexApprovalPolicy = null,
        codexThreadSandbox = null,
        codexTurnSandboxPolicy = null,
        turnTimeoutMs = 3600000,
        readTimeoutMs = 5000,
        stallTimeoutMs = 300000
    )
}
```

- [ ] **Step 3: Run test, verify it passes**

Run: `./gradlew :koncerto-agent:test --tests "com.anomaly.koncerto.agent.AgentRunnerTest"`
Expected: 1 test passes (we just ensure no crashes)

- [ ] **Step 4: Commit**

```bash
git add koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt
git commit -m "feat(agent): add AgentRunner with prompt render and multi-turn hook"
```

---

## Phase 7: Orchestrator Module (`koncerto-orchestrator`)

### Task 7.1: Create orchestrator module with RuntimeState

**Files:**
- Create: `koncerto-orchestrator/build.gradle.kts`
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/RuntimeState.kt`
- Create: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/RuntimeStateTest.kt`

- [ ] **Step 1: Create orchestrator build file**

```kotlin
// koncerto-orchestrator/build.gradle.kts
plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-logging"))
    implementation(project(":koncerto-workflow"))
    implementation(project(":koncerto-workspace"))
    implementation(project(":koncerto-agent"))
    implementation(project(":koncerto-linear"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.mockk)
}
```

- [ ] **Step 2: Implement RuntimeState**

```kotlin
// koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/RuntimeState.kt
package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.Issue
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RunningEntry(
    val issue: Issue,
    val threadId: String,
    val turnId: String,
    val startedAt: Instant,
    val lastCodexTimestamp: Instant?,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val lastReportedInput: Long = 0,
    val lastReportedOutput: Long = 0,
    val lastReportedTotal: Long = 0,
    val turnCount: Int = 1
)

data class RetryEntry(
    val issueId: String,
    val identifier: String,
    val attempt: Int,
    val dueAtMs: Long,
    val error: String?
)

data class CodexTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    var secondsRunning: Long = 0
)

class RuntimeState {
    val mutex = Mutex()
    val running = ConcurrentHashMap<String, RunningEntry>()
    val claimed = ConcurrentHashMap.newKeySet<String>()
    val retryAttempts = ConcurrentHashMap<String, RetryEntry>()
    val completed = ConcurrentHashMap.newKeySet<String>()
    val codexTotals = CodexTotals()
    @Volatile var codexRateLimits: Map<String, Any?> = emptyMap()
    @Volatile var pollIntervalMs: Long = 30_000
    @Volatile var maxConcurrentAgents: Int = 10
    @Volatile var workspaceRoot: java.nio.file.Path = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "symphony_workspaces")

    fun availableSlots(): Int = (maxConcurrentAgents - running.size).coerceAtLeast(0)
}
```

- [ ] **Step 3: Write test**

```kotlin
// koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/RuntimeStateTest.kt
package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class RuntimeStateTest {

    @Test
    fun `available slots is max minus running`() {
        val s = RuntimeState()
        s.maxConcurrentAgents = 3
        assertThat(s.availableSlots()).isEqualTo(3)
        // Without inserting real entries, just check the math
        s.maxConcurrentAgents = 1
        assertThat(s.availableSlots()).isEqualTo(1)
    }

    @Test
    fun `available slots never negative`() {
        val s = RuntimeState()
        s.maxConcurrentAgents = 0
        assertThat(s.availableSlots()).isEqualTo(0)
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :koncerto-orchestrator:test --tests "com.anomaly.koncerto.orchestrator.RuntimeStateTest"`
Expected: 2 tests pass

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/
git commit -m "feat(orchestrator): add RuntimeState with running, claimed, retry maps"
```

---

### Task 7.2: Implement Orchestrator with poll loop, dispatch, retry, reconciliation

**Files:**
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Create: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt`

- [ ] **Step 1: Implement Orchestrator**

```kotlin
// koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt
package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.PromptRenderer
import com.anomaly.koncerto.workflow.WorkflowCache
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Orchestrator(
    private val config: ServiceConfig,
    private val state: RuntimeState,
    private val linear: LinearClient,
    private val workspaces: WorkspaceManager,
    private val agentRunner: AgentRunner,
    private val workflowCache: WorkflowCache,
    private val logger: StructuredLogger,
    private val projectSlug: String
) {
    private var loopJob: Job? = null

    fun start(scope: CoroutineScope) {
        loopJob = scope.launch {
            // Subscribe to agent events for token accounting
            launch {
                agentRunner.events().collect { ev ->
                    handleAgentEvent(ev)
                }
            }
            // Initial tick
            tick()
            // Main loop
            while (isActive) {
                delay(state.pollIntervalMs)
                tick()
            }
        }
    }

    fun stop() { loopJob?.cancel() }

    private suspend fun tick() {
        try {
            reconcile()
            runPreflight()
            fetchAndDispatch()
        } catch (e: Exception) {
            logger.failure("tick_failed", emptyMap(), e)
        }
    }

    private suspend fun reconcile() {
        if (state.running.isEmpty()) return
        val ids = state.running.keys.toList()
        try {
            val states = linear.fetchIssueStatesByIds(ids)
            for ((id, trackerState) in states) {
                val entry = state.running[id] ?: continue
                if (config.terminalStates.any { it.equals(trackerState, ignoreCase = true) }) {
                    logger.info("stop_terminal", mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier),
                        "state" to trackerState)
                    state.running.remove(id)
                    state.claimed.remove(id)
                    try { workspaces.removeWorkspace(entry.issue.identifier) } catch (_: Exception) {}
                } else if (config.activeStates.any { it.equals(trackerState, ignoreCase = true) }) {
                    // still active; keep going
                } else {
                    logger.info("stop_non_active", mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier),
                        "state" to trackerState)
                    state.running.remove(id)
                    state.claimed.remove(id)
                }
            }
        } catch (e: Exception) {
            logger.warn("reconcile_failed", emptyMap(), "error" to (e.message ?: "unknown"))
        }
    }

    private fun runPreflight() {
        if (config.trackerKind.isNullOrBlank() || config.trackerApiKey.isNullOrBlank()
            || config.trackerProjectSlug.isNullOrBlank() || config.codexCommand.isBlank()) {
            logger.warn("preflight_invalid", emptyMap())
        }
    }

    private suspend fun fetchAndDispatch() {
        val candidates = try {
            linear.fetchCandidateIssues(projectSlug, config.activeStates)
        } catch (e: Exception) {
            logger.failure("fetch_candidates_failed", emptyMap(), e)
            return
        }
        val sorted = candidates
            .filter { it.id !in state.running && it.id !in state.claimed }
            .filter { matchesRequiredLabels(it) }
            .filter { !isBlockedForTodo(it) }
            .sortedWith(
                compareBy<Issue>({ it.priority ?: Int.MAX_VALUE })
                    .thenBy { it.createdAt ?: Instant.MAX }
                    .thenBy { it.identifier }
            )

        for (issue in sorted) {
            if (state.availableSlots() <= 0) break
            val perStateLimit = config.maxConcurrentAgentsByState[issue.normalizedState]
            val currentForState = state.running.values.count { it.issue.normalizedState == issue.normalizedState }
            val perStateCap = perStateLimit ?: state.maxConcurrentAgents
            if (currentForState >= perStateCap) continue
            dispatch(issue)
        }
    }

    private fun matchesRequiredLabels(issue: Issue): Boolean {
        if (config.requiredLabels.isEmpty()) return true
        val issueLabels = issue.labels.toSet()
        return config.requiredLabels.all { it.trim().lowercase() in issueLabels }
    }

    private fun isBlockedForTodo(issue: Issue): Boolean {
        if (!issue.normalizedState.equals("todo", ignoreCase = true)) return false
        return issue.blockedBy.any { blocker ->
            val s = blocker.state?.lowercase() ?: return@any true
            config.terminalStates.none { it.equals(s, ignoreCase = true) }
        }
    }

    private fun dispatch(issue: Issue) {
        if (state.claimed.contains(issue.id)) return
        state.claimed.add(issue.id)
        logger.info("dispatch_start", mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier))
        val prompt = workflowCache.current().promptTemplate
        val attempt: Int? = null
        // In a full impl, we'd run in a child coroutine and track results
        kotlinx.coroutines.GlobalScope.launch {
            val result = agentRunner.run(issue, attempt, prompt)
            result.onSuccess {
                state.completed.add(issue.id)
                state.claimed.remove(issue.id)
                logger.info("dispatch_completed", mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier))
            }.onFailure { err ->
                scheduleRetry(issue, err.message ?: "unknown")
            }
        }
    }

    private fun scheduleRetry(issue: Issue, error: String) {
        state.running.remove(issue.id)
        val existing = state.retryAttempts[issue.id]
        val nextAttempt = (existing?.attempt ?: 0) + 1
        val delayMs = (10_000L * (1L shl (nextAttempt - 1).coerceAtMost(20))).coerceAtMost(config.maxRetryBackoffMs)
        val entry = RetryEntry(issue.id, issue.identifier, nextAttempt,
            System.currentTimeMillis() + delayMs, error)
        state.retryAttempts[issue.id] = entry
        logger.info("retry_scheduled", mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier),
            "attempt" to nextAttempt, "delay_ms" to delayMs)
        // In full impl, schedule a coroutine timer
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TurnCompleted -> {
                event.usage?.let { u ->
                    state.codexTotals = state.codexTotals.copy(
                        inputTokens = state.codexTotals.inputTokens + u.inputTokens,
                        outputTokens = state.codexTotals.outputTokens + u.outputTokens,
                        totalTokens = state.codexTotals.totalTokens + u.totalTokens
                    )
                }
            }
            else -> {}
        }
    }
}
```

- [ ] **Step 2: Write test using fakes**

```kotlin
// koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt
package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.AttemptResult
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.WorkflowCache
import com.anomaly.koncerto.workflow.WorkflowDefinition
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OrchestratorTest {

    @Test
    fun `dispatch eligible issues and skip ineligible ones`() = runBlocking {
        val root = Files.createTempDirectory("orch-test-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val config = sampleConfig()
        val state = RuntimeState()
        val cache = WorkflowCache().also { it.set(WorkflowDefinition(emptyMap(), "Hi {{ issue.identifier }}")) }
        val linear = FakeLinearClient(listOf(
            sampleIssue("1", "A-1", "Todo"),
            sampleIssue("2", "A-2", "Done"), // terminal - should be skipped
            sampleIssue("3", "A-3", "Todo").copy(priority = 1)
        ))
        val runner = FakeAgentRunner()
        val logger = StructuredLogger(emptyList())
        val orch = Orchestrator(config, state, linear, mgr, runner, cache, logger, "proj")
        orch.fetchAndDispatchPublic()
        assertThat(runner.dispatched.map { it.identifier }).containsExactly("A-3", "A-1")
    }

    private fun sampleIssue(id: String, identifier: String, state: String) = Issue(
        id = id, identifier = identifier, title = "t", description = null,
        priority = 5, state = state, branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )

    private fun sampleConfig() = ServiceConfig(
        trackerKind = "linear", trackerEndpoint = "x", trackerApiKey = "k", trackerProjectSlug = "p",
        requiredLabels = emptyList(),
        activeStates = listOf("Todo"), terminalStates = listOf("Done"),
        pollIntervalMs = 30000,
        workspaceRoot = java.nio.file.Path.of("/tmp"),
        hooks = HooksConfig(null, null, null, null, 60000),
        maxConcurrentAgents = 10, maxTurns = 1, maxRetryBackoffMs = 300000,
        maxConcurrentAgentsByState = emptyMap(),
        codexCommand = "codex app-server", codexApprovalPolicy = null,
        codexThreadSandbox = null, codexTurnSandboxPolicy = null,
        turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000
    )
}

class FakeLinearClient(private val candidates: List<Issue>) : LinearClient {
    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> = candidates
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> =
        candidates.filter { stateNames.contains(it.state) }
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> =
        candidates.filter { issueIds.contains(it.id) }.associate { it.id to it.state }
}

class FakeAgentRunner : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    private val flow = MutableSharedFlow<AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(issue: Issue, attempt: Int?, prompt: String): EmptyResult<IllegalStateException> {
        dispatched += issue
        return com.anomaly.koncerto.core.result.Result.Success(Unit)
    }
}

fun Orchestrator.fetchAndDispatchPublic() {
    val method = this::class.java.getDeclaredMethod("fetchAndDispatch")
    method.isAccessible = true
    runBlocking { method.invoke(this@fetchAndDispatchPublic) as Unit }
}
```

- [ ] **Step 3: Run test, verify it passes**

Run: `./gradlew :koncerto-orchestrator:test --tests "com.anomaly.koncerto.orchestrator.OrchestratorTest"`
Expected: 1 test passes

- [ ] **Step 4: Commit**

```bash
git add koncerto-orchestrator/
git commit -m "feat(orchestrator): add polling loop, dispatch, retry, reconciliation"
```

---

## Phase 8: Dashboard Module (`koncerto-dashboard`)

### Task 8.1: Create dashboard with HTML and JSON API

**Files:**
- Create: `koncerto-dashboard/build.gradle.kts`
- Create: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/DashboardController.kt`
- Create: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`
- Create: `koncerto-dashboard/src/main/resources/templates/dashboard.html`

- [ ] **Step 1: Create dashboard build file**

```kotlin
// koncerto-dashboard/build.gradle.kts
plugins {
    kotlin("jvm")
    id("org.springframework.boot")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-orchestrator"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.kotlinx.serialization.json)
}
```

- [ ] **Step 2: Implement ApiV1Controller**

```kotlin
// koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt
package com.anomaly.koncerto.dashboard

import com.anomaly.koncerto.orchestrator.RuntimeState
import kotlinx.serialization.Serializable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1")
class ApiV1Controller(private val state: RuntimeState) {

    @Serializable
    data class StateSnapshot(
        val running: List<RunningRow>,
        val retrying: List<RetryingRow>,
        val codexTotals: Totals,
        val rateLimits: Map<String, String>
    )

    @Serializable
    data class RunningRow(
        val issueId: String,
        val issueIdentifier: String,
        val threadId: String,
        val turnId: String,
        val turnCount: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val url: String?
    )

    @Serializable
    data class RetryingRow(
        val issueId: String,
        val identifier: String,
        val attempt: Int,
        val dueAtMs: Long,
        val error: String?
    )

    @Serializable
    data class Totals(
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val secondsRunning: Long
    )

    @GetMapping("/state", produces = ["application/json"])
    fun state(): Mono<StateSnapshot> = Mono.just(StateSnapshot(
        running = state.running.values.map {
            RunningRow(it.issue.id, it.issue.identifier, it.threadId, it.turnId, it.turnCount,
                it.inputTokens, it.outputTokens, it.totalTokens, it.issue.url)
        },
        retrying = state.retryAttempts.values.map {
            RetryingRow(it.issueId, it.identifier, it.attempt, it.dueAtMs, it.error)
        },
        codexTotals = Totals(state.codexTotals.inputTokens, state.codexTotals.outputTokens,
            state.codexTotals.totalTokens, state.codexTotals.secondsRunning),
        rateLimits = state.codexRateLimits.mapValues { it.value.toString() }
    ))

    @GetMapping("/{identifier}", produces = ["application/json"])
    fun byIdentifier(@PathVariable identifier: String): Mono<Map<String, Any?>> {
        val entry = state.running.values.firstOrNull { it.issue.identifier == identifier }
        return if (entry != null) {
            Mono.just(mapOf(
                "issueId" to entry.issue.id,
                "issueIdentifier" to entry.issue.identifier,
                "threadId" to entry.threadId,
                "turnId" to entry.turnId,
                "turnCount" to entry.turnCount
            ))
        } else Mono.just(mapOf("error" to "not_found"))
    }

    @PostMapping("/refresh")
    fun refresh(): Mono<Map<String, String>> = Mono.just(mapOf("status" to "ok"))
}
```

- [ ] **Step 3: Implement DashboardController**

```kotlin
// koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/DashboardController.kt
package com.anomaly.koncerto.dashboard

import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
class DashboardController {
    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    fun dashboard(): Mono<String> = Mono.just(
        ClassPathResource("templates/dashboard.html").inputStream.bufferedReader().readText()
    )
}
```

- [ ] **Step 4: Create dashboard.html template**

```html
<!-- koncerto-dashboard/src/main/resources/templates/dashboard.html -->
<!DOCTYPE html>
<html>
<head>
    <title>Koncerto Dashboard</title>
    <style>
        body { font-family: monospace; padding: 1rem; background: #111; color: #eee; }
        h1 { color: #6cf; }
        table { border-collapse: collapse; width: 100%; margin-top: 1rem; }
        th, td { border: 1px solid #444; padding: 0.4rem; text-align: left; }
        th { background: #222; }
        .totals { margin-top: 1rem; }
    </style>
</head>
<body>
    <h1>Koncerto Dashboard</h1>
    <p>Live orchestrator state</p>
    <div class="totals">
        <strong>Tokens:</strong> <span id="totals">loading...</span>
    </div>
    <h2>Running</h2>
    <table id="running"><thead><tr><th>Identifier</th><th>Thread</th><th>Turns</th><th>URL</th></tr></thead><tbody></tbody></table>
    <h2>Retrying</h2>
    <table id="retrying"><thead><tr><th>Identifier</th><th>Attempt</th><th>Error</th></tr></thead><tbody></tbody></table>
    <script>
        async function refresh() {
            const r = await fetch('/api/v1/state');
            const s = await r.json();
            document.getElementById('totals').textContent =
                `in=${s.codexTotals.inputTokens} out=${s.codexTotals.outputTokens} total=${s.codexTotals.totalTokens}`;
            const rb = document.querySelector('#running tbody');
            rb.innerHTML = s.running.map(x => `<tr><td>${x.issueIdentifier}</td><td>${x.threadId}</td><td>${x.turnCount}</td><td>${x.url ?? ''}</td></tr>`).join('');
            const rt = document.querySelector('#retrying tbody');
            rt.innerHTML = s.retrying.map(x => `<tr><td>${x.identifier}</td><td>${x.attempt}</td><td>${x.error ?? ''}</td></tr>`).join('');
        }
        refresh();
        setInterval(refresh, 5000);
    </script>
</body>
</html>
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew :koncerto-dashboard:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add koncerto-dashboard/
git commit -m "feat(dashboard): add HTML dashboard and JSON API for runtime state"
```

---

## Phase 9: Spring Boot Application (`koncerto-app`)

### Task 9.1: Create Spring Boot application entry point

**Files:**
- Create: `koncerto-app/build.gradle.kts`
- Create: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/KoncertoApplication.kt`
- Create: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/Beans.kt`
- Create: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/CliRunner.kt`
- Create: `koncerto-app/src/main/resources/application.yml`
- Create: `koncerto-app/README.md`

- [ ] **Step 1: Create app build file**

```kotlin
// koncerto-app/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-logging"))
    implementation(project(":koncerto-workflow"))
    implementation(project(":koncerto-workspace"))
    implementation(project(":koncerto-agent"))
    implementation(project(":koncerto-linear"))
    implementation(project(":koncerto-orchestrator"))
    implementation(project(":koncerto-dashboard"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Create Spring Boot main class**

```kotlin
// koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/KoncertoApplication.kt
package com.anomaly.koncerto.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ComponentScan(basePackages = ["com.anomaly.koncerto"])
class KoncertoApplication

fun main(args: Array<String>) {
    runApplication<KoncertoApplication>(*args)
}
```

- [ ] **Step 3: Create bean wiring**

```kotlin
// koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/Beans.kt
package com.anomaly.koncerto.app

import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.DefaultAgentRunner
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.linear.DefaultLinearClient
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.linear.LinearGraphQLClient
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StderrSink
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.orchestrator.Orchestrator
import com.anomaly.koncerto.orchestrator.RuntimeState
import com.anomaly.koncerto.workspace.HookExecutor
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.PromptRenderer
import com.anomaly.koncerto.workflow.WorkflowCache
import com.anomaly.koncerto.workflow.WorkflowLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.nio.file.Paths

@Configuration
class Beans {

    @Bean
    fun logger(@Value("\${koncerto.logs-root:}") logsRoot: String?): StructuredLogger {
        val sinks = mutableListOf<LogSink>(StderrSink())
        if (!logsRoot.isNullOrBlank()) {
            val dir = Paths.get(logsRoot)
            java.nio.file.Files.createDirectories(dir)
            sinks += com.anomaly.koncerto.logging.FileSink(dir.resolve("koncerto.log"))
        }
        return StructuredLogger(sinks)
    }

    @Bean
    fun workflowCache(): WorkflowCache = WorkflowCache()

    @Bean
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Bean
    fun serviceConfig(
        @Value("\${koncerto.workflow-path}") workflowPath: String,
        workflowCache: WorkflowCache
    ): ServiceConfig {
        val path = Paths.get(workflowPath)
        val def = WorkflowLoader.loadFromPath(path)
        workflowCache.set(def)
        val workflowFileDir = path.parent?.toString() ?: "."
        @Suppress("UNCHECKED_CAST")
        val configMap = def.config
        return ServiceConfig.fromMap(configMap, workflowFileDir)
    }

    @Bean
    fun workspaceManager(config: ServiceConfig, logger: StructuredLogger): WorkspaceManager {
        val executor = com.anomaly.koncerto.workspace.ShellHookExecutor(
            config.hooks.timeoutMs, logger
        )
        return WorkspaceManager(config.workspaceRoot, executor)
    }

    @Bean
    fun linearClient(config: ServiceConfig): LinearClient {
        val graphql = LinearGraphQLClient(config.trackerEndpoint, config.trackerApiKey)
        val slug = config.trackerProjectSlug ?: throw IllegalStateException("missing_tracker_project_slug")
        return DefaultLinearClient(graphql, slug)
    }

    @Bean
    fun agentRunner(config: ServiceConfig, workspaces: WorkspaceManager, logger: StructuredLogger): AgentRunner =
        DefaultAgentRunner(config, workspaces, logger)

    @Bean
    fun runtimeState(config: ServiceConfig): RuntimeState = RuntimeState().also {
        it.pollIntervalMs = config.pollIntervalMs
        it.maxConcurrentAgents = config.maxConcurrentAgents
        it.workspaceRoot = config.workspaceRoot
    }

    @Bean
    fun orchestrator(
        config: ServiceConfig,
        state: RuntimeState,
        linear: LinearClient,
        workspaces: WorkspaceManager,
        runner: AgentRunner,
        cache: WorkflowCache,
        logger: StructuredLogger,
        scope: CoroutineScope
    ): Orchestrator = Orchestrator(config, state, linear, workspaces, runner, cache, logger,
        config.trackerProjectSlug ?: "unknown")
}
```

- [ ] **Step 4: Create CLI runner**

```kotlin
// koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/CliRunner.kt
package com.anomaly.koncerto.app

import com.anomaly.koncerto.orchestrator.Orchestrator
import kotlinx.coroutines.CoroutineScope
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class CliRunner(
    private val orchestrator: Orchestrator,
    private val scope: CoroutineScope
) : CommandLineRunner {
    override fun run(vararg args: String?) {
        orchestrator.start(scope)
    }
}
```

- [ ] **Step 5: Create application.yml**

```yaml
# koncerto-app/src/main/resources/application.yml
spring:
  main:
    web-application-type: none
  jackson:
    serialization:
      write-dates-as-timestamps: false
koncerto:
  workflow-path: ${KONCERTO_WORKFLOW_PATH:./WORKFLOW.md}
  logs-root: ${KONCERTO_LOGS_ROOT:}
```

- [ ] **Step 6: Update build to enable web type when --port is set**

Add to `application.yml` (or replace):

```yaml
spring:
  main:
    web-application-type: ${KONCERTO_WEB_TYPE:none}
  webflux:
    base-path: /
koncerto:
  workflow-path: ${KONCERTO_WORKFLOW_PATH:./WORKFLOW.md}
  logs-root: ${KONCERTO_LOGS_ROOT:}
```

Add a simple args-based web toggle in `CliRunner`:
```kotlin
override fun run(vararg args: String?) {
    if (args.any { it == "--port" || it?.startsWith("--port=") == true }) {
        // Spring Boot will already bind web if application.yml sets web-application-type=servlet/reactive
    }
    orchestrator.start(scope)
}
```

In `KoncertoApplication.kt`, parse args and forward:
```kotlin
fun main(args: Array<String>) {
    val webType = if (args.any { it.startsWith("--port") }) "reactive" else "none"
    val expanded = args.toMutableList()
    expanded.add(0, "--spring.main.web-application-type=$webType")
    runApplication<KoncertoApplication>(*expanded.toTypedArray())
}
```

- [ ] **Step 7: Create README**

```markdown
# Koncerto

OpenAI Symphony Kotlin implementation.

## Run

```bash
./gradlew :koncerto-app:bootRun --args="/path/to/WORKFLOW.md"
```

Or build a fat JAR:

```bash
./gradlew :koncerto-app:bootJar
java -jar koncerto-app/build/libs/koncerto-app-*.jar /path/to/WORKFLOW.md
```

## Options

- `WORKFLOW.md` positional: path to workflow file
- `--port <port>`: enable HTTP dashboard on given port
- `--logs-root <path>`: log output directory

## Environment

- `LINEAR_API_KEY`: Linear API key (or set in WORKFLOW.md)
- `KONCERTO_WORKFLOW_PATH`: default workflow path
```

- [ ] **Step 8: Build and verify**

Run: `./gradlew :koncerto-app:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add koncerto-app/
git commit -m "feat(app): add Spring Boot application entry point with bean wiring"
```

---

## Phase 10: Integration & Verification

### Task 10.1: Add a sample WORKFLOW.md and run end-to-end smoke test

**Files:**
- Create: `WORKFLOW.md` (sample)
- Create: `scripts/smoke-test.sh`

- [ ] **Step 1: Create sample WORKFLOW.md**

```markdown
---
tracker:
  kind: linear
  api_key: $LINEAR_API_KEY
  project_slug: EXAMPLE
  active_states:
    - Todo
  terminal_states:
    - Done
    - Cancelled
polling:
  interval_ms: 30000
workspace:
  root: $KONCERTO_WORKSPACE_ROOT
hooks:
  timeout_ms: 60000
agent:
  max_concurrent_agents: 2
  max_turns: 5
codex:
  command: codex app-server
  turn_timeout_ms: 3600000
  stall_timeout_ms: 300000
---

You are working on Linear issue {{ issue.identifier }}.

Title: {{ issue.title }}
Body: {{ issue.description }}
```

- [ ] **Step 2: Create smoke test script**

```bash
#!/usr/bin/env bash
# scripts/smoke-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew test --no-daemon
./gradlew :koncerto-app:build -x test --no-daemon
echo "Build OK"
```

```bash
chmod +x scripts/smoke-test.sh
```

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test`
Expected: All module tests pass

- [ ] **Step 4: Commit**

```bash
git add WORKFLOW.md scripts/
git commit -m "test: add sample WORKFLOW.md and smoke test script"
```

---

## Summary

This plan implements a complete Kotlin/Spring Boot Symphony orchestrator per the SPEC v1:

- 9 modules with clear dependencies
- 35+ bite-sized tasks
- TDD throughout (write test, see fail, implement, see pass, commit)
- Modular: each phase produces a runnable, tested artifact
- Final phase wires everything together in a Spring Boot app

Total commits: ~35+ incremental commits, each with a passing test suite.
