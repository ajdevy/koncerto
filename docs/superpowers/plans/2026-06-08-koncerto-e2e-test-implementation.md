# Koncerto E2E Test Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a `koncerto-e2e` module with a test that spawns a real opencode CLI subprocess, sends a coding task, waits for completion, and verifies the output file.

**Architecture:** New Gradle module with a single `@Tag("e2e")` test class. Uses `OpencodeRuntime` directly (not the Orchestrator dispatch loop, which kills the agent before work completes). Tests the critical untested integration: real agent JSON-RPC handshake + file creation.

**Tech Stack:** Kotlin/JVM, JUnit5, AssertK, kotlinx-coroutines, opencode CLI

---

### Task 1: Create module build file and register in settings

**Files:**
- Create: `koncerto-e2e/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Create `koncerto-e2e/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":koncerto-core"))
    testImplementation(project(":koncerto-logging"))
    testImplementation(project(":koncerto-agent"))
    testImplementation(project(":koncerto-workspace"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

tasks.register<Test>("e2eTest") {
    useJUnitPlatform {
        includeTags("e2e")
    }
    group = "verification"
    description = "Runs end-to-end tests (requires opencode CLI)"
}
```

- [ ] **Step 2: Register module in `settings.gradle.kts`**

Add `"koncerto-e2e"` to the `include(...)` list.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :koncerto-e2e:compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add koncerto-e2e/build.gradle.kts settings.gradle.kts
git commit -m "feat: add koncerto-e2e module with e2e test task"
```

---

### Task 2: Write the E2E test

**Files:**
- Create: `koncerto-e2e/src/test/kotlin/com/anomaly/koncerto/e2e/OpenCodeE2eTest.kt`

**Test flow:**
1. Create temp workspace directory
2. Create `OpencodeRuntime` with command `"opencode"` and workspace path
3. Start runtime
4. Send `initialize`
5. Send `thread/start` with working_directory
6. Send `turn/start` with prompt: "Create a Python script named hello_world.py that prints 'Hello from Koncerto E2E'"
7. Collect events from the events Flow with 180s timeout
8. Verify a `TurnCompleted` event was received
9. Verify `hello_world.py` exists in workspace with correct content
10. Stop runtime, clean up workspace

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p koncerto-e2e/src/test/kotlin/com/anomaly/koncerto/e2e
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.anomaly.koncerto.e2e

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.agent.AgentEvent
import com.anomaly.koncerto.agent.OpencodeRuntime
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("e2e")
class OpenCodeE2eTest {

    private val opencodeCommand: String by lazy {
        checkNotNull(System.getenv("OPENCODE_COMMAND")?.takeIf { it.isNotBlank() }) { "OPENCODE_COMMAND not set" }
    }

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `opencode agent creates hello_world py via real subprocess`() {
        val workspaceDir = Files.createTempDirectory("koncerto-e2e-")
        try {
            val runtime = OpencodeRuntime(opencodeCommand, workspaceDir, noopLogger())

            val started = runBlocking { runtime.start() }
            assertThat(started).isEqualTo(true)

            runtime.send("initialize", null)
            runtime.send(
                "thread/start", buildJsonObject {
                    put("working_directory", workspaceDir.toString())
                }
            )
            runtime.send(
                "turn/start", buildJsonObject {
                    put("input", "Create a Python script named hello_world.py " +
                        "in the workspace root. The script should print " +
                        "'Hello from Koncerto E2E' when executed.")
                }
            )

            val collected = mutableListOf<AgentEvent>()
            runBlocking {
                withTimeoutOrNull(180_000) {
                    runtime.events().collect { ev ->
                        collected += ev
                        if (ev is AgentEvent.TurnCompleted ||
                            ev is AgentEvent.TurnFailed ||
                            ev is AgentEvent.TurnCancelled
                        ) {
                            return@collect
                        }
                    }
                }
            }

            runtime.stop()

            val turnCompleted = collected.filterIsInstance<AgentEvent.TurnCompleted>().firstOrNull()
            assertThat(turnCompleted).isNotNull()

            val helloFile = workspaceDir.resolve("hello_world.py")
            assertThat(helloFile.exists()).isTrue()
            assertThat(helloFile.readText()).contains("Hello from Koncerto E2E")
        } finally {
            workspaceDir.toFile().deleteRecursively()
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :koncerto-e2e:compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Verify test discovery**

Run: `./gradlew :koncerto-e2e:test --tests "*OpenCodeE2eTest*"`
Expected: Test should be skipped (tag 'e2e' excluded)

Run: `./gradlew :koncerto-e2e:e2eTest`
Expected: Test fails with clear error (OPENCODE_COMMAND not set) or passes if env var is set

- [ ] **Step 5: Commit**

```bash
git add koncerto-e2e/src/
git commit -m "feat: add E2E test for opencode agent real subprocess"
```

---

### Task 3: Verify with actual opencode CLI

- [ ] **Step 1: Run E2E test locally**

```bash
OPENCODE_COMMAND=opencode ./gradlew :koncerto-e2e:e2eTest
```

Expected: BUILD SUCCESSFUL (test creates hello_world.py via real opencode agent, verifies content)

- [ ] **Step 2: Run full build to confirm e2e tests are excluded**

```bash
./gradlew build -Pjacoco
```

Expected: BUILD SUCCESSFUL, e2e tests not run

- [ ] **Step 3: Push**

```bash
git push
```
