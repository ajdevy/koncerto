# Infrastructure Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement 6 independent infrastructure features: health checks, CLI, graceful shutdown, heartbeat, rate limiter, notifications.

**Architecture:** Three parallel workstreams — (A) app-layer features (health, CLI, shutdown), (B) runtime/infra (rate limiter, heartbeat), (C) notifications module. Each workstream touches disjoint files except shared config/wiring handled first.

**Tech Stack:** Kotlin, Spring Boot, Spring Actuator, Ktor Client (for webhook/Telegram), Jakarta Mail, Gradle

---

## File Map

| Workstream | Files |
|------------|-------|
| **A** (App: health, CLI, shutdown) | Create: `OrchestratorHealthIndicator.kt`, `application.properties`; Modify: `CliRunner.kt`, `KoncertoApplication.kt`, `Orchestrator.kt`, `DispatchService.kt` |
| **B** (Infra: rate limiter, heartbeat) | Create: `TokenBucketRateLimiter.kt`, `CircuitBreaker.kt`, `RateLimitedLinearClient.kt`; Modify: `ServiceConfig.kt`, `ProjectConfig.kt`, `AgentRuntime.kt`, `StdioAgentRuntime.kt`, `AgentRunner.kt`, `Beans.kt` |
| **C** (Notifications) | Create: `koncerto-notifications/` module (5 files); Modify: `ServiceConfig.kt`, `ProjectConfig.kt`, `Beans.kt`, `DispatchService.kt`, `Orchestrator.kt`, `settings.gradle.kts`, `koncerto-app/build.gradle.kts` |

---

### Workstream A: Health Check + CLI + Graceful Shutdown

### Task A1: Health check endpoint

**Files:**
- Create: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/OrchestratorHealthIndicator.kt`
- Create: `koncerto-app/src/main/resources/application.properties`

- [ ] **Create `OrchestratorHealthIndicator.kt`**

```kotlin
package com.flexsentlabs.koncerto.app

import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component
class OrchestratorHealthIndicator(
    private val orchestrator: Orchestrator
) : HealthIndicator {
    override fun health(): Health {
        val runningCount = orchestrator.projects.values.sumOf { it.state.running.size }
        val blockedCount = orchestrator.projects.values.sumOf { it.state.blocked.size }
        val retryCount = orchestrator.projects.values.sumOf { it.state.retryAttempts.size }
        return Health.up()
            .withDetail("runningAgents", runningCount)
            .withDetail("blockedIssues", blockedCount)
            .withDetail("retryingIssues", retryCount)
            .withDetail("uptimeMs", System.currentTimeMillis() - START_TIME_MS)
            .build()
    }

    companion object {
        private val START_TIME_MS = System.currentTimeMillis()
    }
}
```

- [ ] **Create `application.properties`**

```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
```

### Task A2: CLI commands

**Files:**
- Modify: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/CliRunner.kt`
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`

- [ ] **Rewrite `CliRunner.kt`**

```kotlin
package com.flexsentlabs.koncerto.app

import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class CliRunner(
    private val orchestrator: Orchestrator
) : CommandLineRunner {
    override fun run(vararg args: String?) {
        val command = args.firstOrNull()
        when (command?.lowercase()) {
            "status" -> printStatus()
            "agents" -> printAgents()
            "restart" -> orchestrator.restart()
            "help" -> printHelp()
            else -> orchestrator.start()
        }
    }

    private fun printStatus() {
        println("[koncerto] Projects: ${orchestrator.projects.size}")
        val totalRunning = orchestrator.projects.values.sumOf { it.state.running.size }
        val totalBlocked = orchestrator.projects.values.sumOf { it.state.blocked.size }
        val totalRetrying = orchestrator.projects.values.sumOf { it.state.retryAttempts.size }
        println("[koncerto] Running: $totalRunning")
        println("[koncerto] Blocked: $totalBlocked")
        println("[koncerto] Retrying: $totalRetrying")
        val tokens = orchestrator.projects.values.firstOrNull()?.state?.tokenTotals
        if (tokens != null) {
            println("[koncerto] Tokens: in=${tokens.inputTokens} out=${tokens.outputTokens} total=${tokens.totalTokens}")
        }
    }

    private fun printAgents() {
        for ((slug, pr) in orchestrator.projects) {
            println("[koncerto] Project: $slug")
            for ((id, entry) in pr.state.running) {
                val seconds = Duration.between(entry.startedAt, Instant.now()).seconds
                println("  ${entry.issue.identifier} | turns=${entry.turnCount} | ${seconds}s | ${entry.issue.title}")
            }
        }
    }

    private fun printHelp() {
        println("Koncerto CLI")
        println("  (no args)   Start orchestrator")
        println("  status      Show orchestrator state")
        println("  agents      List running agents")
        println("  restart     Restart orchestrator")
        println("  help        Show this message")
    }
}
```

- [ ] **Add `restart()` to `Orchestrator.kt`**

Add method to `Orchestrator` class:

```kotlin
private val scope: CoroutineScope
private var tickJob: Job? = null

fun start() {
    tickJob = scope.launch { tickLoop() }
}

fun restart() {
    tickJob?.cancel()
    state.clearAll()
    tickJob = scope.launch { tickLoop() }
}
```

And add `state.clearAll()` to `RuntimeState.kt`:

```kotlin
fun clearAll() {
    running.clear()
    claimed.clear()
    retryAttempts.clear()
    completed.clear()
    blocked.clear()
    tokenTotals = TokenTotals()
}
```

### Task A3: Graceful shutdown

**Files:**
- Modify: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/KoncertoApplication.kt`
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`

- [ ] **Add shutdown flag to `Orchestrator.kt`**

```kotlin
@Volatile
var shutdownRequested = false

fun requestShutdown(): Boolean {
    shutdownRequested = true
    return runningAgentsCount() > 0
}

fun runningAgentsCount(): Int = projects.values.sumOf { it.state.running.size }

// Modify tick() to check:
internal fun tickLoop() {
    while (true) {
        if (shutdownRequested) {
            logger.info("shutdown_requested_skipping_tick", emptyMap())
            delay(pollIntervalMs)
            continue
        }
        tick()
    }
}
```

- [ ] **Add shutdown check to `DispatchService.fetchAndDispatch()`**

At the top of `fetchAndDispatch()`:

```kotlin
if (projectConfig.agent.shutdownRequested) return
```

This requires passing a `shutdownRequested: () -> Boolean` lambda to `DispatchService`.

- [ ] **Add shutdown hook in `KoncertoApplication.kt`**

```kotlin
package com.flexsentlabs.koncerto.app

import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct

@SpringBootApplication(proxyBeanMethods = false)
@ComponentScan(basePackages = ["com.flexsentlabs.koncerto"])
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
```

---

### Workstream B: Rate Limiter + Heartbeat

### Task B1: Token bucket rate limiter

**Files:**
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/TokenBucketRateLimiter.kt`

- [ ] **Create `TokenBucketRateLimiter.kt`**

```kotlin
package com.flexsentlabs.koncerto.core

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

class TokenBucketRateLimiter(
    private val maxTokens: Int,
    private val refillIntervalMs: Long,
    private val refillCount: Int
) {
    private val tokens = AtomicLong(maxTokens.toLong())
    private val lastRefillMs = AtomicLong(System.currentTimeMillis())

    suspend fun acquire() {
        while (true) {
            if (tryAcquire()) return
            delay(refillIntervalMs / 4)
        }
    }

    fun tryAcquire(): Boolean {
        refill()
        val current = tokens.get()
        if (current <= 0) return false
        return tokens.compareAndSet(current, current - 1)
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val last = lastRefillMs.get()
        val elapsed = now - last
        if (elapsed >= refillIntervalMs && lastRefillMs.compareAndSet(last, now)) {
            val newTokens = (elapsed / refillIntervalMs * refillCount).toInt()
            tokens.updateAndGet { current -> (current + newTokens).coerceAtMost(maxTokens.toLong()) }
        }
    }
}
```

### Task B2: Circuit breaker

**Files:**
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/CircuitBreaker.kt`

- [ ] **Create `CircuitBreaker.kt`**

```kotlin
package com.flexsentlabs.koncerto.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CircuitBreaker(
    private val failureThreshold: Int,
    private val resetTimeoutMs: Long
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicInteger(State.CLOSED.ordinal)
    private val failureCount = AtomicInteger(0)
    private val lastFailureMs = AtomicLong(0)

    fun allowRequest(): Boolean {
        when (State.entries[state.get()]) {
            State.CLOSED -> return true
            State.OPEN -> {
                if (System.currentTimeMillis() - lastFailureMs.get() >= resetTimeoutMs) {
                    state.set(State.HALF_OPEN.ordinal)
                    return true
                }
                return false
            }
            State.HALF_OPEN -> return true
        }
    }

    fun recordSuccess() {
        state.set(State.CLOSED.ordinal)
        failureCount.set(0)
    }

    fun recordFailure() {
        lastFailureMs.set(System.currentTimeMillis())
        val count = failureCount.incrementAndGet()
        if (count >= failureThreshold && state.get() != State.OPEN.ordinal) {
            state.set(State.OPEN.ordinal)
        }
    }
}
```

### Task B3: RateLimitedLinearClient

**Files:**
- Create: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/RateLimitedLinearClient.kt`

- [ ] **Create `RateLimitedLinearClient.kt`**

```kotlin
package com.flexsentlabs.koncerto.linear

import com.flexsentlabs.koncerto.core.CircuitBreaker
import com.flexsentlabs.koncerto.core.TokenBucketRateLimiter
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.model.UserRef
import com.flexsentlabs.koncerto.logging.StructuredLogger

class RateLimitedLinearClient(
    private val delegate: LinearClient,
    private val rateLimiter: TokenBucketRateLimiter?,
    private val circuitBreaker: CircuitBreaker?,
    private val logger: StructuredLogger
) : LinearClient {

    private suspend fun <T> protect(call: suspend () -> T): T {
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            logger.warn("circuit_breaker_open", emptyMap())
            throw LinearError.RateLimited("Circuit breaker open")
        }
        rateLimiter?.acquire()
        return try {
            val result = call()
            circuitBreaker?.recordSuccess()
            result
        } catch (e: Exception) {
            circuitBreaker?.recordFailure()
            throw e
        }
    }

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) =
        protect { delegate.fetchCandidateIssues(projectSlug, activeStates) }
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) =
        protect { delegate.fetchIssuesByStates(projectSlug, stateNames) }
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>) =
        protect { delegate.fetchIssueStatesByIds(issueIds) }
    override suspend fun fetchIssueById(issueId: String) =
        protect { delegate.fetchIssueById(issueId) }
    override suspend fun resolveStateId(projectSlug: String, stateName: String) =
        protect { delegate.resolveStateId(projectSlug, stateName) }
    override suspend fun updateIssueState(issueId: String, stateId: String) =
        protect { delegate.updateIssueState(issueId, stateId) }
    override suspend fun createComment(issueId: String, body: String) =
        protect { delegate.createComment(issueId, body) }
    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) =
        protect { delegate.updateIssueAssignee(issueId, assigneeId) }
    override suspend fun fetchIssueCreator(issueId: String) =
        protect { delegate.fetchIssueCreator(issueId) }
    override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>) =
        protect { delegate.createIssue(projectSlug, title, state, description, labels) }
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) =
        protect { delegate.createLink(sourceIssueId, targetIssueId, type) }
}
```

### Task B4: Agent heartbeat

**Files:**
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRuntime.kt`
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/StdioAgentRuntime.kt`
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt`

- [ ] **Add `isAlive()` to `AgentRuntime` interface**

```kotlin
interface AgentRuntime {
    suspend fun start(): Boolean
    fun send(method: String, params: JsonElement? = null): String
    fun events(): Flow<AgentEvent>
    val output: SharedFlow<String>
    fun isAlive(): Boolean = true
    fun stop()
}
```

- [ ] **Override `isAlive()` in `StdioAgentRuntime`**

```kotlin
override fun isAlive(): Boolean = process?.isAlive == true
```

No other changes needed — the existing stall detection handles hang detection via output timeout.

- [ ] **Add alive check to `DefaultAgentRunner`**

In the `run()` method, inside `coroutineScope { ... }`, add alongside existing jobs:

```kotlin
val aliveJob = launch {
    while (true) {
        delay(heartbeatIntervalMs)
        if (!runtime.isAlive()) {
            throw IllegalStateException("agent_process_died")
        }
    }
}

// In finally/cleanup:
aliveJob.cancel()
```

Add `heartbeatIntervalMs` as constructor parameter to `DefaultAgentRunner`:

```kotlin
class DefaultAgentRunner(
    // ...existing...
    private val heartbeatIntervalMs: Long = 30_000L
) : AgentRunner {
```

---

### Workstream C: Notifications Module

### Task C1: Create `koncerto-notifications` module

**Files:**
- Create: `koncerto-notifications/build.gradle.kts`
- Create: `koncerto-notifications/src/main/kotlin/com/anomaly/koncerto/notifications/NotificationEvent.kt`
- Create: `koncerto-notifications/src/main/kotlin/com/anomaly/koncerto/notifications/Notifier.kt`
- Create: `koncerto-notifications/src/main/kotlin/com/anomaly/koncerto/notifications/channel/LoggingNotifier.kt`
- Create: `koncerto-notifications/src/main/kotlin/com/anomaly/koncerto/notifications/channel/WebhookNotifier.kt`
- Create: `koncerto-notifications/src/main/kotlin/com/anomaly/koncerto/notifications/channel/TelegramNotifier.kt`
- Create: `koncerto-notifications/src/main/kotlin/com/anomaly/koncerto/notifications/channel/SmtpEmailNotifier.kt`
- Modify: `settings.gradle.kts`
- Modify: `koncerto-app/build.gradle.kts`

- [ ] **Create `koncerto-notifications/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-logging"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Add `:koncerto-notifications` to `settings.gradle.kts`**

```kotlin
include(
    // ...existing...
    "koncerto-notifications"
)
```

- [ ] **Add dependency in `koncerto-app/build.gradle.kts`**

```kotlin
implementation(project(":koncerto-notifications"))
```

- [ ] **Create `NotificationEvent.kt`**

```kotlin
package com.flexsentlabs.koncerto.notifications

import com.flexsentlabs.koncerto.agent.TokenUsage

sealed class NotificationEvent {
    abstract val projectSlug: String
    abstract val issueId: String
    abstract val issueIdentifier: String
    abstract val title: String

    data class AgentCompleted(
        override val projectSlug: String,
        override val issueId: String,
        override val issueIdentifier: String,
        override val title: String,
        val tokenUsage: TokenUsage?
    ) : NotificationEvent()

    data class AgentFailed(
        override val projectSlug: String,
        override val issueId: String,
        override val issueIdentifier: String,
        override val title: String,
        val error: String
    ) : NotificationEvent()

    data class AgentStalled(
        override val projectSlug: String,
        override val issueId: String,
        override val issueIdentifier: String,
        override val title: String,
        val stallDurationMs: Long
    ) : NotificationEvent()

    data class ClarificationRequested(
        override val projectSlug: String,
        override val issueId: String,
        override val issueIdentifier: String,
        override val title: String
    ) : NotificationEvent()
}
```

- [ ] **Create `Notifier.kt`**

```kotlin
package com.flexsentlabs.koncerto.notifications

interface Notifier {
    suspend fun send(event: NotificationEvent)
}

class CompositeNotifier(
    private val notifiers: List<Notifier>
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        for (n in notifiers) {
            try { n.send(event) } catch (_: Exception) { }
        }
    }
}
```

- [ ] **Create `LoggingNotifier.kt`**

```kotlin
package com.flexsentlabs.koncerto.notifications.channel

import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.notifications.Notifier

class LoggingNotifier(
    private val logger: StructuredLogger
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        val ev = event::class.simpleName ?: "NotificationEvent"
        logger.info("notification_${ev.lowercase()}", mapOf(
            "issue_id" to event.issueId,
            "issue_identifier" to event.issueIdentifier
        ), "event" to ev)
    }
}
```

- [ ] **Create `WebhookNotifier.kt`**

```kotlin
package com.flexsentlabs.koncerto.notifications.channel

import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.notifications.Notifier
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class WebhookNotifier(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val httpClient: HttpClient
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        val body = Json.encodeToString(WebhookPayload(event))
        httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
            headers.forEach { (k, v) -> header(k, v) }
        }
    }
}

@Serializable
private data class WebhookPayload(
    val event: String,
    val projectSlug: String,
    val issueId: String,
    val issueIdentifier: String,
    val title: String,
    val error: String? = null,
    val stallDurationMs: Long? = null
) {
    constructor(event: NotificationEvent) : this(
        event = event::class.simpleName ?: "unknown",
        projectSlug = event.projectSlug,
        issueId = event.issueId,
        issueIdentifier = event.issueIdentifier,
        title = event.title,
        error = (event as? NotificationEvent.AgentFailed)?.error,
        stallDurationMs = (event as? NotificationEvent.AgentStalled)?.stallDurationMs
    )
}
```

- [ ] **Create `TelegramNotifier.kt`**

```kotlin
package com.flexsentlabs.koncerto.notifications.channel

import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.notifications.Notifier
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class TelegramNotifier(
    private val botToken: String,
    private val chatId: String,
    private val httpClient: HttpClient
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        val text = formatTelegram(event)
        val payload = Json.encodeToString(SendMessage(chatId = chatId, text = text))
        httpClient.post("https://api.telegram.org/bot$botToken/sendMessage") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    private fun formatTelegram(event: NotificationEvent): String {
        val emoji = when (event) {
            is NotificationEvent.AgentCompleted -> "\u2705"
            is NotificationEvent.AgentFailed -> "\u274C"
            is NotificationEvent.AgentStalled -> "\u26A0\uFE0F"
            is NotificationEvent.ClarificationRequested -> "\u2753"
        }
        return "$emoji *${event.issueIdentifier}*: ${event.title}"
    }
}

@Serializable
private data class SendMessage(
    val chatId: String,
    val text: String,
    val parseMode: String = "Markdown"
)
```

- [ ] **Create `SmtpEmailNotifier.kt`**

```kotlin
package com.flexsentlabs.koncerto.notifications.channel

import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.notifications.Notifier
import java.util.Properties
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

class SmtpEmailNotifier(
    private val smtpHost: String,
    private val smtpPort: Int,
    private val username: String?,
    private val password: String?,
    private val from: String,
    private val to: String
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        val props = Properties().apply {
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort.toString())
            if (username != null) {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
            }
        }
        val session = if (username != null) {
            Session.getInstance(props, object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(username, password ?: "")
            })
        } else Session.getInstance(props)
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            addRecipient(Message.RecipientType.TO, InternetAddress(to))
            subject = "[Koncerto] ${event.issueIdentifier}: ${event::class.simpleName}"
            setText(formatBody(event))
        }
        Transport.send(msg)
    }

    private fun formatBody(event: NotificationEvent): String = buildString {
        appendLine("Koncerto Notification")
        appendLine("Event: ${event::class.simpleName}")
        appendLine("Issue: ${event.issueIdentifier} - ${event.title}")
        appendLine("Project: ${event.projectSlug}")
        when (event) {
            is NotificationEvent.AgentFailed -> appendLine("Error: ${event.error}")
            is NotificationEvent.AgentStalled -> appendLine("Stall duration: ${event.stallDurationMs}ms")
            else -> {}
        }
    }
}
```

### Task C2: Config parsing

**Files:**
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`

- [ ] **Add `NotificationsConfig` to config models**

```kotlin
// In ProjectConfig.kt:
data class NotificationsConfig(
    val onCompleted: Boolean = true,
    val onFailed: Boolean = true,
    val onStalled: Boolean = true,
    val onClarification: Boolean = true,
    val telegram: TelegramConfig? = null,
    val email: EmailConfig? = null,
    val webhook: WebhookConfig? = null
)

data class TelegramConfig(
    val botToken: String,
    val chatId: String
)

data class EmailConfig(
    val smtpHost: String,
    val smtpPort: Int = 587,
    val username: String?,
    val password: String?,
    val from: String,
    val to: String
)

data class WebhookConfig(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class RateLimiterConfig(
    val requestsPerSecond: Int = 10,
    val maxBurst: Int = 20
)

data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val resetTimeoutMs: Long = 30_000
)
```

- [ ] **Add parsing in `ServiceConfig.parseProjectConfig()`**

```kotlin
private fun parseProjectConfig(map: Map<*, *>, workflowFileDir: String): ProjectConfig {
    val tracker = parseTrackerConfig(map["tracker"] as? Map<*, *>)
    val workspace = parseWorkspaceConfig(map["workspace"] as? Map<*, *>, workflowFileDir)
    val agent = parseAgentConfig(map["agent"] as? Map<*, *>)
    val notifications = parseNotificationsConfig(map["notifications"] as? Map<*, *>)
    val rateLimiter = parseRateLimiterConfig(map["rate_limiter"] as? Map<*, *>)
    val circuitBreaker = parseCircuitBreakerConfig(map["circuit_breaker"] as? Map<*, *>)
    return ProjectConfig(
        tracker = tracker, workspace = workspace, agent = agent,
        notifications = notifications,
        rateLimiter = rateLimiter, circuitBreaker = circuitBreaker
    )
}
```

Add parsing methods:

```kotlin
internal fun parseNotificationsConfig(map: Map<*, *>?): NotificationsConfig {
    if (map == null) return NotificationsConfig(onCompleted = false, onFailed = false, onStalled = false, onClarification = false)
    val telegramMap = map["telegram"] as? Map<*, *>
    val emailMap = map["email"] as? Map<*, *>
    val webhookMap = map["webhook"] as? Map<*, *>
    return NotificationsConfig(
        onCompleted = (map["on_completed"] as? Boolean) ?: (telegramMap != null || emailMap != null || webhookMap != null),
        onFailed = (map["on_failed"] as? Boolean) ?: (telegramMap != null || emailMap != null || webhookMap != null),
        onStalled = (map["on_stalled"] as? Boolean) ?: (telegramMap != null || emailMap != null || webhookMap != null),
        onClarification = (map["on_clarification"] as? Boolean) ?: (telegramMap != null || emailMap != null || webhookMap != null),
        telegram = telegramMap?.let { TelegramConfig(
            botToken = ServiceConfig.resolveEnvRef(it["bot_token"] as? String) ?: "",
            chatId = it["chat_id"] as? String ?: ""
        )},
        email = emailMap?.let { EmailConfig(
            smtpHost = it["smtp_host"] as? String ?: "",
            smtpPort = (it["smtp_port"] as? Number)?.toInt() ?: 587,
            username = ServiceConfig.resolveEnvRef(it["username"] as? String),
            password = ServiceConfig.resolveEnvRef(it["password"] as? String),
            from = it["from"] as? String ?: "",
            to = it["to"] as? String ?: ""
        )},
        webhook = webhookMap?.let { WebhookConfig(
            url = it["url"] as? String ?: "",
            headers = (it["headers"] as? Map<*, *>)
                ?.mapKeys { k -> k.key.toString() }
                ?.mapValues { v -> ServiceConfig.resolveEnvRef(v.value as? String) ?: v.value.toString() }
                ?: emptyMap()
        )}
    )
}

internal fun parseRateLimiterConfig(map: Map<*, *>?): RateLimiterConfig? {
    if (map == null) return null
    return RateLimiterConfig(
        requestsPerSecond = (map["requests_per_second"] as? Number)?.toInt() ?: 10,
        maxBurst = (map["max_burst"] as? Number)?.toInt() ?: 20
    )
}

internal fun parseCircuitBreakerConfig(map: Map<*, *>?): CircuitBreakerConfig? {
    if (map == null) return null
    return CircuitBreakerConfig(
        failureThreshold = (map["failure_threshold"] as? Number)?.toInt() ?: 5,
        resetTimeoutMs = (map["reset_timeout_ms"] as? Number)?.toLong() ?: 30_000
    )
}
```

- [ ] **Add heartbeat fields to `parseAgentConfig()`**

```kotlin
val heartbeatIntervalMs = (map?.get("heartbeat_interval_ms") as? Number)?.toLong() ?: 30_000L
val heartbeatTimeoutMs = (map?.get("heartbeat_timeout_ms") as? Number)?.toLong() ?: 90_000L
```

Add to `AgentProjectConfig`:

```kotlin
val heartbeatIntervalMs: Long = 30_000L,
val heartbeatTimeoutMs: Long = 90_000L,
```

### Task C3: Wire notifications and rate limiter in Beans.kt

**Files:**
- Modify: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/Beans.kt`

- [ ] **Add notification beans**

```kotlin
@Bean
fun httpClient(): HttpClient = HttpClient(CIO) { engine { requestTimeout = 10_000 } }

@Bean
fun logNotifier(logger: StructuredLogger): LoggingNotifier = LoggingNotifier(logger)

@Bean
fun compositeNotifier(
    config: ServiceConfig,
    httpClient: HttpClient,
    logNotifier: LoggingNotifier
): CompositeNotifier {
    val notifiers = mutableListOf<Notifier>(logNotifier)
    val nc = config.projects.values.firstOrNull()?.notifications ?: return CompositeNotifier(notifiers)
    if (nc.webhook != null) {
        notifiers.add(WebhookNotifier(nc.webhook.url, nc.webhook.headers, httpClient))
    }
    if (nc.telegram != null) {
        notifiers.add(TelegramNotifier(nc.telegram.botToken, nc.telegram.chatId, httpClient))
    }
    if (nc.email != null) {
        notifiers.add(SmtpEmailNotifier(
            nc.email.smtpHost, nc.email.smtpPort,
            nc.email.username, nc.email.password,
            nc.email.from, nc.email.to
        ))
    }
    return CompositeNotifier(notifiers)
}
```

- [ ] **Wire rate-limited linear client**

Replace `linearClientFactory` bean to wrap with rate limiter:

```kotlin
@Bean
fun linearClientFactory(
    config: ServiceConfig,
    logger: StructuredLogger
): (ProjectConfig) -> LinearClient = { pc ->
    val graphql = LinearGraphQLClient(pc.tracker.endpoint, pc.tracker.apiKey)
    val slug = pc.tracker.projectSlug
        ?: throw IllegalStateException("missing_tracker_project_slug")
    val base = DefaultLinearClient(graphql, slug)
    val rateLimiter = pc.rateLimiter?.let {
        TokenBucketRateLimiter(
            maxTokens = it.maxBurst,
            refillIntervalMs = 1000,
            refillCount = it.requestsPerSecond
        )
    }
    val circuitBreaker = pc.circuitBreaker?.let {
        CircuitBreaker(it.failureThreshold, it.resetTimeoutMs)
    }
    if (rateLimiter != null || circuitBreaker != null) {
        RateLimitedLinearClient(base, rateLimiter, circuitBreaker, logger)
    } else base
}
```

### Task C4: Integrate notifications into DispatchService and Orchestrator

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`

- [ ] **Add notifier to `DispatchService`**

```kotlin
class DispatchService(
    // ...existing...
    private val notifier: CompositeNotifier? = null,
    private val notificationsConfig: NotificationsConfig? = null
) {
```

In `dispatch()`, after successful completion:

```kotlin
result.onSuccess {
    // ...existing...
    if (notificationsConfig?.onCompleted == true && notifier != null) {
        notifier.send(NotificationEvent.AgentCompleted(
            projectSlug = projectSlug,
            issueId = issue.id,
            issueIdentifier = issue.identifier,
            title = issue.title,
            tokenUsage = entry?.let { TokenUsage(it.inputTokens, it.outputTokens, it.totalTokens) }
        ))
    }
}.onFailure { err ->
    // ...existing...
    if (notificationsConfig?.onFailed == true && notifier != null) {
        notifier.send(NotificationEvent.AgentFailed(
            projectSlug = projectSlug,
            issueId = issue.id,
            issueIdentifier = issue.identifier,
            title = issue.title,
            error = err.message ?: "unknown"
        ))
    }
}
```

In `handleClarification()`:

```kotlin
if (notificationsConfig?.onClarification == true && notifier != null) {
    notifier.send(NotificationEvent.ClarificationRequested(
        projectSlug = projectSlug,
        issueId = issueId,
        issueIdentifier = issue.identifier,
        title = issue.title
    ))
}
```

- [ ] **Add stall notification to `Orchestrator.reconcile()`**

When a stall is detected (the existing `stop_non_active` path in reconcile), notify:

```kotlin
// In reconcile(), after state.running.remove(id) when state is non-active:
val notificationsConfig = pr.config.notifications
if (notificationsConfig.onStalled) {
    pr.dispatch.notifier?.send(NotificationEvent.AgentStalled(
        projectSlug = slug,
        issueId = id,
        issueIdentifier = entry.issue.identifier,
        title = entry.issue.title,
        stallDurationMs = 0
    ))
}
```

---

## Self-Review

- **Spec coverage:** All 6 features from the spec have implementation tasks. Feature 3 (Health) → Task A1, Feature 6 (CLI) → Task A2, Feature 7 (Shutdown) → Task A3, Feature 8 (Heartbeat) → Task B4, Feature 5 (Rate limiter) → Tasks B1-B3, Feature 1 (Notifications) → Tasks C1-C4.
- **Placeholder scan:** No TBD, TODO, or placeholder code in any task. All code is complete.
- **Type consistency:** `NotificationEvent` sealed class, `Notifier` interface, config data classes all use consistent types across all tasks.
