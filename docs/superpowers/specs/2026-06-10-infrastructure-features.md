# Infrastructure Features — Design Spec

**Date:** 2026-06-10
**Status:** Design

## Overview

Six independent infrastructure features for Koncerto:

1. **Notifications** — Telegram, email, and webhook alerts on agent events
2. **Health check endpoint** — Monitor orchestrator health via Spring Actuator
3. **Rate limiter / circuit breaker** — Protect Linear API from bursts and failures
4. **CLI commands** — Query orchestrator state from the terminal
5. **Graceful shutdown** — Drain running agents before process exit
6. **Agent heartbeat monitoring** — Detect unresponsive agent subprocesses

---

## 1. Notifications

### 1.1 Event Model

```kotlin
sealed class NotificationEvent {
    data class AgentCompleted(
        val projectSlug: String, val issueId: String, val issueIdentifier: String,
        val title: String, val tokenUsage: TokenUsage?
    ) : NotificationEvent()
    data class AgentFailed(
        val projectSlug: String, val issueId: String, val issueIdentifier: String,
        val title: String, val error: String
    ) : NotificationEvent()
    data class AgentStalled(
        val projectSlug: String, val issueId: String, val issueIdentifier: String,
        val title: String, val stallDurationMs: Long
    ) : NotificationEvent()
    data class ClarificationRequested(
        val projectSlug: String, val issueId: String, val issueIdentifier: String,
        val title: String
    ) : NotificationEvent()
}
```

### 1.2 Notifier Interface

```kotlin
interface Notifier {
    suspend fun send(event: NotificationEvent)
}
```

### 1.3 Implementations

| Notifier | Mechanism | Config |
|----------|-----------|--------|
| `LoggingNotifier` | Writes to structured logger (always active) | None |
| `WebhookNotifier` | POST JSON to configured URL | `url`, `headers` |
| `TelegramNotifier` | POST to `https://api.telegram.org/bot<token>/sendMessage` | `bot_token`, `chat_id` |
| `SmtpEmailNotifier` | Jakarta Mail SMTP | `smtp_host`, `smtp_port`, `username`, `password`, `from`, `to` |

### 1.4 Config (WORKFLOW.md)

```yaml
notifications:
  on_completed: true
  on_failed: true
  on_stalled: true
  on_clarification: true
  telegram:
    bot_token: $TELEGRAM_BOT_TOKEN
    chat_id: "-1001234567890"
  email:
    smtp_host: smtp.gmail.com
    smtp_port: 587
    username: $SMTP_USERNAME
    password: $SMTP_PASSWORD
    from: koncerto@example.com
    to: team@example.com
  webhook:
    url: https://hooks.example.com/koncerto
    headers:
      X-Api-Key: $WEBHOOK_API_KEY
```

All notification sections are optional. Each event type defaults to `true` when a channel is configured (e.g., if `telegram` section exists, telegram gets all enabled events). Individual event flags let you filter.

### 1.5 Integration Points

- `DispatchService.dispatch()` — `onSuccess` path calls `notifier.send(AgentCompleted)`, `onFailure` path calls `notifier.send(AgentFailed)`
- `Orchestrator.reconcile()` — when stall detected, calls `notifier.send(AgentStalled)`
- `DispatchService.handleClarification()` — calls `notifier.send(ClarificationRequested)`

### 1.6 Module

New module `koncerto-notifications` with sub-packages:
- `com.anomaly.koncerto.notifications` — `Notifier`, `NotificationEvent`
- `com.anomaly.koncerto.notifications.config` — `NotificationsConfig` parsing
- `com.anomaly.koncerto.notifications.channel` — `LoggingNotifier`, `WebhookNotifier`, `TelegramNotifier`, `SmtpEmailNotifier`
- `com.anomaly.koncerto.notifications.test` — `FakeNotifier` for tests

Dependencies: `koncerto-core` (for event types, config models), `koncerto-logging`.

### 1.7 Testing

- Each notifier implementation tested with a fake HTTP server or mock SMTP server
- `FakeNotifier` records events for assertion
- Config parsing tests for YAML notification section
- Integration: `DispatchService` with `FakeNotifier` verifies events fire on completion/failure

---

## 2. Health Check Endpoint

### 2.1 Approach

Spring Boot Actuator is already on the classpath (`spring-boot-starter-actuator` in `koncerto-app`). Enable it with minimal configuration.

### 2.2 Config

Add `application.properties` or `application.yml` in `koncerto-app/src/main/resources`:

```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
```

### 2.3 Custom Health Indicator

```kotlin
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
            .withDetail("uptimeMs", System.currentTimeMillis() - startTimeMs)
            .build()
    }

    companion object {
        private val startTimeMs = System.currentTimeMillis()
    }
}
```

### 2.4 Files changed

| File | Change |
|------|--------|
| `koncerto-app/src/main/resources/application.properties` | New file with Actuator config |
| `koncerto-app/src/main/kotlin/.../OrchestratorHealthIndicator.kt` | New file |
| (optional) `ApiV1Controller` test | Verify `/health` works |

No build changes needed — Actuator is already declared.

### 2.5 Testing

- Start app context, `GET /health` returns 200 with JSON body
- `health()` returns `UP` with expected detail keys when no agents running
- Custom indicator shows correct counts

---

## 3. Rate Limiter / Circuit Breaker

### 3.1 Token Bucket Rate Limiter

```kotlin
class TokenBucketRateLimiter(
    private val maxTokens: Int,
    private val refillIntervalMs: Long,
    private val refillCount: Int
) {
    suspend fun acquire(): Boolean
    fun tryAcquire(): Boolean  // non-blocking
}
```

Token bucket: starts with `maxTokens`. Every `refillIntervalMs`, adds `refillCount` tokens (capped at `maxTokens`). `acquire()` suspends until a token is available. `tryAcquire()` returns false immediately if no token.

### 3.2 Circuit Breaker

```kotlin
class CircuitBreaker(
    private val failureThreshold: Int,
    private val resetTimeoutMs: Long
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    fun recordSuccess()
    fun recordFailure()
    fun allowRequest(): Boolean
}
```

States: CLOSED (normal), OPEN (rejecting), HALF_OPEN (test request after timeout). Transitions: CLOSED → OPEN after `failureThreshold` consecutive failures. OPEN → HALF_OPEN after `resetTimeoutMs`. HALF_OPEN → CLOSED on success, → OPEN on failure.

### 3.3 LinearClient Decorator

```kotlin
class RateLimitedLinearClient(
    private val delegate: LinearClient,
    private val rateLimiter: TokenBucketRateLimiter,
    private val circuitBreaker: CircuitBreaker,
    private val logger: StructuredLogger
) : LinearClient by delegate {
    // Proxy all methods:
    // 1. Check circuitBreaker.allowRequest()
    // 2. rateLimiter.acquire()
    // 3. Call delegate method
    // 4. On success → circuitBreaker.recordSuccess()
    // 5. On exception → circuitBreaker.recordFailure()
}
```

Using `by delegate` (Kotlin delegation) with overridden methods. All `suspend` functions wrap the call.

### 3.4 Config

Added to `WORKFLOW.md` top-level:

```yaml
rate_limiter:
  requests_per_second: 10
  max_burst: 20
circuit_breaker:
  failure_threshold: 5
  reset_timeout_ms: 30_000
```

Both sections optional — if absent, no rate limiting/circuit breaker is applied (pass-through).

### 3.5 Files changed

| File | Change |
|------|--------|
| `koncerto-core/.../config/ServiceConfig.kt` | Parse `rate_limiter` and `circuit_breaker` sections |
| `koncerto-core/.../config/ProjectConfig.kt` | Add `RateLimiterConfig`, `CircuitBreakerConfig` |
| `koncerto-linear/.../RateLimitedLinearClient.kt` | New decorator class |
| `koncerto-core/.../TokenBucketRateLimiter.kt` | New file |
| `koncerto-core/.../CircuitBreaker.kt` | New file |
| `koncerto-app/.../Beans.kt` | Wire `RateLimitedLinearClient` wrapping `DefaultLinearClient` |

### 3.6 Testing

- `TokenBucketRateLimiter`: acquire/release cycle, exhaustion, refill
- `CircuitBreaker`: closed → open → half-open → closed cycle
- `RateLimitedLinearClient`: success/failure recording, circuit open blocks requests
- Config parsing: valid/invalid rate limiter YAML

---

## 4. CLI Commands

### 4.1 Approach

Extend the existing `CliRunner` (Spring `CommandLineRunner`) with a `--command` flag. Since the orchestrator is in-process, we have direct access to state.

### 4.2 Commands

| Command | Action |
|---------|--------|
| `(default)` | Start orchestrator (existing behavior) |
| `status` | Print running/retrying/blocked/token state to stdout |
| `agents` | List running agents with identifiers, turn count, duration |
| `restart` | Signal orchestrator to restart (stop + start) |
| `help` | Print available commands |

### 4.3 Implementation

```kotlin
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
            else -> orchestrator.start()  // default
        }
    }

    private fun printStatus() {
        val totalRunning = orchestrator.projects.values.sumOf { it.state.running.size }
        val totalBlocked = orchestrator.projects.values.sumOf { it.state.blocked.size }
        val totalRetrying = orchestrator.projects.values.sumOf { it.state.retryAttempts.size }
        println("Projects: ${orchestrator.projects.size}")
        println("Running: $totalRunning")
        println("Blocked: $totalBlocked")
        println("Retrying: $totalRetrying")
    }

    private fun printAgents() {
        for ((slug, pr) in orchestrator.projects) {
            println("Project: $slug")
            for ((id, entry) in pr.state.running) {
                val seconds = java.time.Duration.between(entry.startedAt, java.time.Instant.now()).seconds
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
    }
}
```

### 4.4 Orchestrator.restart()

Add `restart()` method to `Orchestrator`: cancels current scope, clears state, starts new scope (re-uses existing tick loop logic).

### 4.5 Files changed

| File | Change |
|------|--------|
| `koncerto-app/.../CliRunner.kt` | Rewrite with command dispatch |
| `koncerto-orchestrator/.../Orchestrator.kt` | Add `restart()` method |

---

## 5. Graceful Shutdown

### 5.1 Approach

Register a JVM shutdown hook that signals agents to stop, waits for completion, then force-kills remaining.

### 5.2 Shutdown Hook

```kotlin
// In KoncertoApplication.main()
Runtime.getRuntime().addShutdownHook(Thread {
    // 1. Set shutdown requested flag
    // 2. Wait up to 30s for running agents
    // 3. Force-kill remaining
})
```

### 5.3 Orchestrator Changes

```kotlin
@Volatile
var shutdownRequested = false

fun requestShutdown(): Boolean {
    shutdownRequested = true
    // Don't accept new dispatches
    return runningAgentsCount() > 0
}

fun runningAgentsCount(): Int = projects.values.sumOf { it.state.running.size }
```

`tick()` checks `shutdownRequested` before dispatching and skips if true. A separate `awaitShutdown(timeoutMs)` waits for running agents to drain.

### 5.4 Integration

```kotlin
// Beans.kt - register shutdown hook
val orchestratorRef = orchestrator
Runtime.getRuntime().addShutdownHook(Thread {
    println("Shutdown requested, draining ${orchestratorRef.runningAgentsCount()} agents...")
    orchestratorRef.requestShutdown()
    runBlocking { withTimeout(30_000) {
        while (orchestratorRef.runningAgentsCount() > 0) delay(500)
    }}
})
```

### 5.5 Files changed

| File | Change |
|------|--------|
| `koncerto-app/.../KoncertoApplication.kt` | Add shutdown hook registration |
| `koncerto-orchestrator/.../Orchestrator.kt` | Add `shutdownRequested`, `requestShutdown()`, `runningAgentsCount()` |
| `koncerto-orchestrator/.../DispatchService.kt` | Check `shutdownRequested` before `fetchAndDispatch()` |

### 5.6 Testing

- Unit: `Orchestrator.requestShutdown()` sets flag, prevents new dispatches
- Unit: `runningAgentsCount()` returns correct count

---

## 6. Agent Heartbeat Monitoring

### 6.1 Approach

Agent subprocess sends periodic heartbeat JSON-RPC notifications. Orchestrator tracks last heartbeat per agent and marks agents as stalled if heartbeat exceeds timeout.

### 6.2 Process-Alive Check

Instead of JSON-RPC pings (which adds protocol overhead), the heartbeat is a simple process-alive check:

```kotlin
// In DefaultAgentRunner.run(), alongside the existing stall watcher:
val aliveCheck = launch {
    while (true) {
        delay(heartbeatIntervalMs)
        if (runtime.isAlive() == false) {
            throw IllegalStateException("Agent process died unexpectedly")
        }
    }
}
```

### 6.3 AgentRuntime Interface

Add a non-suspending check:

```kotlin
interface AgentRuntime {
    // ...existing methods...
    fun isAlive(): Boolean  // default: process != null && process.isAlive
}
```

`StdioAgentRuntime` implements by checking `process?.isAlive == true`. Default in interface returns `true` for non-process-based runtimes.

### 6.4 DefaultAgentRunner Heartbeat Tracking

The alive-check coroutine runs alongside existing `outputJob` and `stallJob`. If `isAlive()` returns false, throw `IllegalStateException("agent_process_died")`. No separate timeout needed — check frequency = `heartbeatIntervalMs`. Default: 30s.

### 6.5 Config

```yaml
agent:
  heartbeat_interval_ms: 30000   # how often agent sends heartbeat
  heartbeat_timeout_ms: 90000    # how long before considering agent dead
```

Defaults: 30s interval, 90s timeout. Existing `agent` section in WORKFLOW.md — just add new fields.

### 6.6 Files changed

| File | Change |
|------|--------|
| `koncerto-core/.../config/ProjectConfig.kt` | Add `heartbeatIntervalMs`, `heartbeatTimeoutMs` |
| `koncerto-core/.../config/ServiceConfig.kt` | Parse new fields in `parseAgentConfig()` |
| `koncerto-agent/.../StdioAgentRuntime.kt` | Add heartbeat launch in `start()`, cancel in `stop()` |
| `koncerto-agent/.../AgentRunner.kt` | Use heartbeat timeouts, add heartbeat watcher in `DefaultAgentRunner.run()` |

### 6.7 Testing

- Unit: `StdioAgentRuntime` sends heartbeat at interval (verify via mock process)
- Unit: `DefaultAgentRunner` detects missed heartbeat → throws timeout
- Unit: Config parsing of new fields

---

## Implementation Order

```
Day 1:  3 (Health) + 6 (CLI)       — smallest, independent
Day 1:  7 (Graceful shutdown)       — medium, builds on 4
Day 2:  8 (Heartbeat)               — medium, agent runtime changes
Day 2:  5 (Rate limiter)            — medium, core utility
Day 3:  1 (Notifications)           — largest, new module
```

Each feature is independent. Features 3, 6, 7 are trivially small. Features 8, 5, 1 build on existing patterns.

---

## Files Changed Summary

| File | Features |
|------|----------|
| `koncerto-core/.../config/ServiceConfig.kt` | 1, 5, 8 |
| `koncerto-core/.../config/ProjectConfig.kt` | 1, 5, 8 |
| `koncerto-core/.../TokenBucketRateLimiter.kt` | 5 (new) |
| `koncerto-core/.../CircuitBreaker.kt` | 5 (new) |
| `koncerto-linear/.../RateLimitedLinearClient.kt` | 5 (new) |
| `koncerto-linear/.../LinearClient.kt` | 5 (interface unchanged) |
| `koncerto-notifications/` | 1 (new module) |
| `koncerto-app/build.gradle.kts` | 1 (add `:koncerto-notifications`) |
| `koncerto-app/.../Beans.kt` | 1, 5 (wiring) |
| `koncerto-app/.../KoncertoApplication.kt` | 7 (shutdown hook) |
| `koncerto-app/.../CliRunner.kt` | 6 (rewrite) |
| `koncerto-app/.../OrchestratorHealthIndicator.kt` | 3 (new) |
| `koncerto-app/src/main/resources/application.properties` | 3 (new) |
| `koncerto-orchestrator/.../Orchestrator.kt` | 6, 7 (restart, shutdown) |
| `koncerto-orchestrator/.../DispatchService.kt` | 1, 7 (events, shutdown check) |
| `koncerto-agent/.../StdioAgentRuntime.kt` | 8 (heartbeat) |
| `koncerto-agent/.../AgentRunner.kt` | 8 (heartbeat watcher) |
