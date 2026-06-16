# TDD, Conventional Commits & Clarifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add TDD-by-default prompts, conventional commit messages, and a human-in-the-loop clarification workflow (agent writes `CLARIFICATION.md` → orchestrator adds Linear comment + blocks + assigns).

**Architecture:** Three independent additions to the existing pipeline: (1) prompt text change, (2) GitWorkflow commit message format change, (3) new ClarificationRequested AgentEvent + LinearClient methods (createComment, updateIssueAssignee, fetchIssueCreator) + DispatchService.handleClarification() + Orchestrator event routing.

**Tech Stack:** Kotlin/JVM, Spring Boot 3.3+, Linear GraphQL API, JUnit5 + assertk + Turbine for testing

---

### File Structure

| File | Change |
|------|--------|
| `koncerto-core/.../model/Issue.kt` | Add `UserRef` data class, `creator: UserRef?` on `Issue` |
| `koncerto-core/.../config/ServiceConfig.kt` | Add `blockedState`, `projectAdmin` fields + parsing |
| `koncerto-agent/.../AgentEvent.kt` | Add `ClarificationRequested` event |
| `koncerto-agent/.../AgentRunner.kt` | Detect `.koncerto/clarification.md` after runtime stops; emit event |
| `koncerto-linear/.../LinearClient.kt` (interface) | Add `createComment`, `updateIssueAssignee`, `fetchIssueCreator` |
| `koncerto-linear/.../LinearClient.kt` (DefaultLinearClient) | Implement new methods + GraphQL queries/mutations |
| `koncerto-linear/.../IssueMapper.kt` | Parse `creator` from GraphQL issue node |
| `koncerto-orchestrator/.../DispatchService.kt` | Add `handleClarification()` |
| `koncerto-orchestrator/.../Orchestrator.kt` | Route `ClarificationRequested` to `dispatchService.handleClarification()` |
| `koncerto-workspace/.../GitWorkflow.kt` | Conventional commit prefix from labels |
| `prompts/implement.md` | Add TDD + conventional commit instructions |
| `WORKFLOW.md` | Add `blocked_state`, `project_admin` |
| `koncerto-core/.../config/ServiceConfigTest.kt` | Tests for new config fields |
| `koncerto-agent/.../AgentEventTest.kt` | Test `ClarificationRequested` serialization |
| `koncerto-agent/.../AgentRunnerTest.kt` | Test clarification file detection |
| `koncerto-linear/.../LinearClientTest.kt` | Tests for new GraphQL operations |
| `koncerto-workspace/.../GitWorkflowTest.kt` | Test conventional commit prefix |
| `koncerto-orchestrator/.../OrchestratorTest.kt` | Test event routing (update 3 fake LinearClient classes) |
| `koncerto-orchestrator/.../DispatchServiceTest.kt` | Test `handleClarification()` (update 3 fake LinearClient classes) |
| `koncerto-dashboard/.../ApiV1ControllerTest.kt` | Update Issue constructor calls if `creator` is required |

---

### Task 1: Add `UserRef` and `creator` field to Issue model

**Files:**
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/model/Issue.kt`
- Test: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/model/IssueTest.kt`

- [ ] **Step 1: Write failing test for UserRef model**

```kotlin
// In koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/model/IssueTest.kt
@Test
fun `UserRef has expected fields`() {
    val ref = UserRef(id = "user-1", displayName = "Alice", isBot = false)
    assertThat(ref.id).isEqualTo("user-1")
    assertThat(ref.displayName).isEqualTo("Alice")
    assertThat(ref.isBot).isEqualTo(false)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :koncerto-core:test --tests "*IssueTest*" --rerun-tasks`
Expected: compilation error — `UserRef` not defined

- [ ] **Step 3: Add UserRef + creator field**

```kotlin
// In koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/model/Issue.kt — add before BlockerRef
data class UserRef(
    val id: String,
    val displayName: String,
    val isBot: Boolean
)
```

Add `creator` field to Issue:

```kotlin
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
    val creator: UserRef? = null,
    val createdAt: Instant?,
    val updatedAt: Instant?
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :koncerto-core:test --tests "*IssueTest*" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/model/Issue.kt koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/model/IssueTest.kt
git commit -m "feat: add UserRef model and creator field to Issue"
```

---

### Task 2: Add blockedState and projectAdmin to ServiceConfig

**Files:**
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`
- Test: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// In ServiceConfigTest.kt — add these tests after git config tests
@Test
fun `blockedState defaults to Blocked when tracker has no blocked_state`() {
    val config = ServiceConfig.fromMap(emptyMap(), workflowFileDir = "/tmp")
    assertThat(config.blockedState).isEqualTo("Blocked")
}

@Test
fun `blockedState parsed from tracker section`() {
    val config = ServiceConfig.fromMap(
        mapOf("tracker" to mapOf("kind" to "linear", "project_slug" to "p",
            "api_key" to "k", "blocked_state" to "Waiting")),
        workflowFileDir = "/tmp"
    )
    assertThat(config.blockedState).isEqualTo("Waiting")
}

@Test
fun `projectAdmin defaults to null`() {
    val config = ServiceConfig.fromMap(emptyMap(), workflowFileDir = "/tmp")
    assertThat(config.projectAdmin).isNull()
}

@Test
fun `projectAdmin parsed from tracker section`() {
    val config = ServiceConfig.fromMap(
        mapOf("tracker" to mapOf("kind" to "linear", "project_slug" to "p",
            "api_key" to "k", "project_admin" to "user-1")),
        workflowFileDir = "/tmp"
    )
    assertThat(config.projectAdmin).isEqualTo("user-1")
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :koncerto-core:test --tests "*ServiceConfigTest*" --rerun-tasks`
Expected: compilation error — `blockedState`, `projectAdmin` not on ServiceConfig

- [ ] **Step 3: Add fields and parsing to ServiceConfig**

Add fields to `ServiceConfig` data class:

```kotlin
val blockedState: String = "Blocked",
val projectAdmin: String? = null,
```

Add to `TrackerSection`:

```kotlin
val blockedState: String,
val projectAdmin: String?
```

In `parseTrackerSection`, add:

```kotlin
val blockedState = (map?.get("blocked_state") as? String) ?: "Blocked"
val projectAdmin = map?.get("project_admin") as? String
```

Pass them in the `TrackerSection` constructor and then into `ServiceConfig`:

```kotlin
TrackerSection(
    kind = kind,
    endpoint = endpoint,
    apiKey = apiKey,
    projectSlug = projectSlug,
    requiredLabels = requiredLabels,
    activeStates = activeStates,
    terminalStates = terminalStates,
    blockedState = blockedState,
    projectAdmin = projectAdmin
)
```

And in the ServiceConfig constructor call (from `fromMapOrError`):

```kotlin
blockedState = trackerSection.blockedState,
projectAdmin = trackerSection.projectAdmin,
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :koncerto-core:test --tests "*ServiceConfigTest*" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt
git commit -m "feat: add blockedState and projectAdmin to ServiceConfig"
```

---

### Task 3: Add new LinearClient methods (interface + implementation + GraphQL)

**Files:**
- Modify: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearClient.kt` (interface + DefaultLinearClient)
- Modify: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/IssueMapper.kt`
- Test: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearClientTest.kt`
- Test: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearClientTest.kt` (IssueMapperTests section)

- [ ] **Step 1: Write failing tests**

Add to the IssueMapperTests section:

```kotlin
@Test
fun `map issue with creator`() {
    val json = buildJsonObject {
        put("id", "1")
        put("identifier", "ABC-1")
        put("title", "Test")
        put("state", buildJsonObject { put("name", "Todo") })
        put("labels", buildJsonObject { put("nodes", buildJsonArray { }) })
        put("blockedBy", buildJsonObject { put("nodes", buildJsonArray { }) })
        put("creator", buildJsonObject {
            put("id", "user-1")
            put("displayName", "Alice")
            put("isBot", JsonPrimitive(false))
        })
    }
    val issue = IssueMapper.fromLinear(json)
    assertThat(issue.creator).isNotNull()
    assertThat(issue.creator!!.id).isEqualTo("user-1")
    assertThat(issue.creator!!.displayName).isEqualTo("Alice")
    assertThat(issue.creator!!.isBot).isEqualTo(false)
}

@Test
fun `map issue without creator returns null`() {
    val json = buildJsonObject {
        put("id", "1")
        put("identifier", "ABC-2")
        put("title", "No creator")
        put("state", buildJsonObject { put("name", "Todo") })
        put("labels", buildJsonObject { put("nodes", buildJsonArray { }) })
        put("blockedBy", buildJsonObject { put("nodes", buildJsonArray { }) })
    }
    val issue = IssueMapper.fromLinear(json)
    assertThat(issue.creator).isNull()
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :koncerto-linear:test --tests "*IssueMapperTests*" --rerun-tasks`
Expected: compilation error — `creator` not parsed in IssueMapper

- [ ] **Step 3: Add `creator` parsing to IssueMapper**

Add to `IssueMapper.fromLinear()` after `blockedBy` parsing:

```kotlin
val creatorNode = node["creator"] as? JsonObject
val creator = creatorNode?.let {
    UserRef(
        id = it.string("id"),
        displayName = it.string("displayName"),
        isBot = (it["isBot"] as? JsonPrimitive)?.content?.toBoolean() ?: false
    )
}
```

And add `creator = creator` to the `Issue` constructor call.

Add the import:
```kotlin
import com.flexsentlabs.koncerto.core.model.UserRef
```

- [ ] **Step 4: Run mapper tests again**

Run: `./gradlew :koncerto-linear:test --tests "*IssueMapperTests*" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Add GraphQL queries and mutations to DefaultLinearClient**

Add these inside `DefaultLinearClient`:

```kotlin
internal val commentCreateMutation = """
    mutation CommentCreate(${'$'}issueId: String!, ${'$'}body: String!) {
      commentCreate(input: { issueId: ${'$'}issueId, body: ${'$'}body }) { success comment { id } }
    }
""".trimIndent()

internal val issueUpdateWithAssigneeMutation = """
    mutation IssueUpdate(${'$'}id: String!, ${'$'}stateId: String, ${'$'}assigneeId: String) {
      issueUpdate(id: ${'$'}id, input: { stateId: ${'$'}stateId, assigneeId: ${'$'}assigneeId }) { success }
    }
""".trimIndent()
```

Modify `issueByIdQuery` to include `creator`:

```graphql
creator { id displayName isBot }
```

Add after the `state { name }` line in `issueByIdQuery`.

- [ ] **Step 6: Implement new interface methods**

Add to `LinearClient` interface:

```kotlin
suspend fun createComment(issueId: String, body: String): String?
suspend fun updateIssueAssignee(issueId: String, userId: String)
suspend fun fetchIssueCreator(issueId: String): UserRef?
```

Add imports to LinearClient.kt:
```kotlin
import com.flexsentlabs.koncerto.core.model.UserRef
```

Implement in `DefaultLinearClient`:

```kotlin
override suspend fun createComment(issueId: String, body: String): String? {
    val vars = buildJsonObject {
        put("issueId", issueId)
        put("body", body)
    }
    val resp = graphql.execute(commentCreateMutation, vars)
    val result = (resp["data"] as? JsonObject)?.get("commentCreate") as? JsonObject
    val success = (result?.get("success") as? JsonPrimitive)?.content?.toBoolean() ?: false
    if (!success) return null
    val comment = result["comment"] as? JsonObject
    return (comment?.get("id") as? JsonPrimitive)?.content
}

override suspend fun updateIssueAssignee(issueId: String, userId: String) {
    val vars = buildJsonObject {
        put("id", issueId)
        put("assigneeId", userId)
    }
    graphql.execute(issueUpdateWithAssigneeMutation, vars)
}

override suspend fun fetchIssueCreator(issueId: String): UserRef? {
    val issue = fetchIssueById(issueId) ?: return null
    return issue.creator
}
```

- [ ] **Step 7: Write LinearClient unit tests for new operations**

Add to the `LinearGraphQLClientTests` section (or create a new nested class):

```kotlin
@Nested
inner class NewApiTests {
    private val graphql = FakeGraphqlClient()

    @Test
    fun `createComment sends correct mutation`() = runTest {
        val client = DefaultLinearClient(graphql, "p")
        client.createComment("issue-1", "Hello")

        val (query, vars) = graphql.calls.last()
        assertThat(query).contains("commentCreate")
        assertThat(vars["issueId"]?.toString()).contains("issue-1")
        assertThat(vars["body"]?.toString()).contains("Hello")
    }

    @Test
    fun `createComment returns null on error`() = runTest {
        val graphql = FakeGraphqlClient(
            responses = mutableListOf(buildJsonObject {
                put("data", buildJsonObject {
                    put("commentCreate", buildJsonObject {
                        put("success", JsonPrimitive(false))
                    })
                })
            })
        )
        val client = DefaultLinearClient(graphql, "p")
        val result = client.createComment("issue-1", "body")
        assertThat(result).isNull()
    }

    @Test
    fun `updateIssueAssignee sends correct mutation`() = runTest {
        val client = DefaultLinearClient(graphql, "p")
        client.updateIssueAssignee("issue-1", "user-1")

        val (query, vars) = graphql.calls.last()
        assertThat(query).contains("issueUpdate")
        assertThat(vars["assigneeId"]?.toString()).contains("user-1")
    }

    @Test
    fun `fetchIssueCreator returns null for issue without creator`() = runTest {
        val client = DefaultLinearClient(graphql, "p")
        val creator = client.fetchIssueCreator("nonexistent")
        assertThat(creator).isNull()
    }
}
```

- [ ] **Step 8: Run all linear tests**

Run: `./gradlew :koncerto-linear:test --rerun-tasks`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add koncerto-linear/
git commit -m "feat: add createComment, updateIssueAssignee, fetchIssueCreator to LinearClient"
```

---

### Task 4: Add ClarificationRequested AgentEvent

**Files:**
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentEvent.kt`
- Test: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentEventTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// In AgentEventTest.kt
@Test
fun `ClarificationRequested has issueId and question`() {
    val event = AgentEvent.ClarificationRequested(
        issueId = "issue-1",
        question = "What should the default value be?",
        pid = 1L,
        timestamp = ts
    )
    assertThat(event.issueId).isEqualTo("issue-1")
    assertThat(event.question).isEqualTo("What should the default value be?")
    assertThat(event.pid).isEqualTo(1L)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :koncerto-agent:test --tests "*AgentEventTest*" --rerun-tasks`
Expected: compilation error — `ClarificationRequested` not defined

- [ ] **Step 3: Add ClarificationRequested to AgentEvent sealed class**

```kotlin
data class ClarificationRequested(
    val issueId: String,
    val question: String,
    override val pid: Long?,
    override val timestamp: Instant = Instant.now()
) : AgentEvent()
```

Add it after `ApprovalAutoApproved` (anywhere in the sealed class body).

- [ ] **Step 4: Run tests**

Run: `./gradlew :koncerto-agent:test --tests "*AgentEventTest*" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentEvent.kt koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentEventTest.kt
git commit -m "feat: add ClarificationRequested AgentEvent"
```

---

### Task 5: Detect clarifications in AgentRunner

**Files:**
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt`
- Test: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt`

- [ ] **Step 1: Write failing test for clarification detection**

```kotlin
// In AgentRunnerTest.kt — add at the end
@Test
fun `runner emits ClarificationRequested when clarification file exists`() = runTest {
    val root = Files.createTempDirectory("agent-runner-clarify-")
    val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
    val config = sampleConfig(command = "false")
    val runner = DefaultAgentRunner(config, mgr, noopLogger())
    val issue = sampleIssue()

    // Create the clarification file BEFORE running
    val wsPath = root.resolve("ABC-1")
    Files.createDirectories(wsPath.resolve(".koncerto"))
    wsPath.resolve(".koncerto/clarification.md").writeText("What should I do?")

    val events = mutableListOf<AgentEvent>()
    val job = launch {
        runner.events().collect { events.add(it) }
    }

    runner.run(issue, attempt = null, prompt = "test")
    job.cancel()

    val clarified = events.filterIsInstance<AgentEvent.ClarificationRequested>().firstOrNull()
    assertThat(clarified).isNotNull()
    assertThat(clarified!!.issueId).isEqualTo("1")
    assertThat(clarified.question).isEqualTo("What should I do?")
}

@Test
fun `runner does not emit ClarificationRequested without clarification file`() = runTest {
    val root = Files.createTempDirectory("agent-runner-no-clarify-")
    val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
    val config = sampleConfig(command = "false")
    val runner = DefaultAgentRunner(config, mgr, noopLogger())
    val issue = sampleIssue()

    val events = mutableListOf<AgentEvent>()
    val job = launch {
        runner.events().collect { events.add(it) }
    }

    runner.run(issue, attempt = null, prompt = "test")
    job.cancel()

    val clarified = events.filterIsInstance<AgentEvent.ClarificationRequested>()
    assertThat(clarified.size).isEqualTo(0)
}
```

Add import:
```kotlin
import kotlinx.coroutines.launch
import kotlin.io.path.writeText
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :koncerto-agent:test --tests "*AgentRunnerTest*" --rerun-tasks`
Expected: compilation error — no clarification detection in AgentRunner

- [ ] **Step 3: Add clarification detection to DefaultAgentRunner.run()**

After the existing `config.hooks.afterRun` line and before the closing `}`, add:

```kotlin
val clarificationFile = workspace.path.resolve(".koncerto/clarification.md")
if (clarificationFile.toFile().exists()) {
    val question = clarificationFile.toFile().readText().trim()
    clarificationFile.toFile().delete()
    eventFlow.tryEmit(AgentEvent.ClarificationRequested(
        issueId = issue.id,
        question = question
    ))
    logger.info("clarification_requested", mapOf(
        "issue_id" to issue.id,
        "issue_identifier" to issue.identifier
    ))
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :koncerto-agent:test --tests "*AgentRunnerTest*" --rerun-tasks`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt
git commit -m "feat: detect CLARIFICATION.md and emit ClarificationRequested event"
```

---

### Task 6: Add handleClarification to DispatchService

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Modify: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt` (update 3 fake LinearClients)
- Modify: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt` (add handleClarification tests)

- [ ] **Step 1: Update all 3 fake LinearClients in DispatchServiceTest.kt**

Add `createComment`, `updateIssueAssignee`, `fetchIssueCreator` to:

**`SimpleLinear`:**
```kotlin
override suspend fun createComment(issueId: String, body: String): String? = null
override suspend fun updateIssueAssignee(issueId: String, userId: String) {}
override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
```

**`TrackingLinearClient`:**
```kotlin
var createdCommentIssueId: String? = null
var createdCommentBody: String? = null
var assignedIssueId: String? = null
var assignedUserId: String? = null
private var creatorMap: Map<String, UserRef> = emptyMap()

fun withCreators(map: Map<String, UserRef>) { creatorMap = map }

override suspend fun createComment(issueId: String, body: String): String? {
    createdCommentIssueId = issueId
    createdCommentBody = body
    return "comment-1"
}
override suspend fun updateIssueAssignee(issueId: String, userId: String) {
    assignedIssueId = issueId
    assignedUserId = userId
}
override suspend fun fetchIssueCreator(issueId: String): UserRef? = creatorMap[issueId]
```

**`ThrowingLinearClient`:**
```kotlin
override suspend fun createComment(issueId: String, body: String): String? =
    throw RuntimeException("API down")
override suspend fun updateIssueAssignee(issueId: String, userId: String) =
    throw RuntimeException("API down")
override suspend fun fetchIssueCreator(issueId: String): UserRef? =
    throw RuntimeException("API down")
```

Add import to DispatchServiceTest.kt:
```kotlin
import com.flexsentlabs.koncerto.core.model.UserRef
```

- [ ] **Step 2: Update sampleConfig in DispatchServiceTest**

Add `blockedState` and `projectAdmin` to the `config()` helper method:

```kotlin
fun config(stages: Map<String, StageAgentConfig> = emptyMap()) = ServiceConfig(
    // ...existing fields...
    blockedState = "Blocked",
    projectAdmin = null,
    // ...gitConfig already there...
)
```

- [ ] **Step 3: Write handleClarification tests**

```kotlin
// In DispatchServiceTest — add to the test class
@Test
fun `handleClarification adds comment updates state and assigns`() = runTest {
    val linear = TrackingLinearClient()
    linear.withCreators(mapOf("issue-1" to UserRef("user-1", "Alice", isBot = false)))
    val state = RuntimeState().apply { maxConcurrentAgents = 10 }
    val service = DispatchService(config(), state, linear, CollectingAgentRunner(), cache, noopLogger(), "p")

    service.handleClarification("issue-1", "What API endpoint?")

    assertThat(linear.createdCommentIssueId).isEqualTo("issue-1")
    assertThat(linear.createdCommentBody).contains("What API endpoint?")
    assertThat(linear.transitionedIssueId).isEqualTo("issue-1")
    assertThat(linear.assignedIssueId).isEqualTo("issue-1")
    assertThat(linear.assignedUserId).isEqualTo("user-1")
}

@Test
fun `handleClarification falls back to projectAdmin when creator is bot`() = runTest {
    val linear = TrackingLinearClient()
    linear.withCreators(mapOf("issue-1" to UserRef("bot-1", "Bot", isBot = true)))
    val cfg = config().copy(projectAdmin = "admin-1")
    val state = RuntimeState().apply { maxConcurrentAgents = 10 }
    val service = DispatchService(cfg, state, linear, CollectingAgentRunner(), cache, noopLogger(), "p")

    service.handleClarification("issue-1", "Question")

    assertThat(linear.assignedUserId).isEqualTo("admin-1")
}

@Test
fun `handleClarification does not assign when no creator and no projectAdmin`() = runTest {
    val linear = TrackingLinearClient()
    val state = RuntimeState().apply { maxConcurrentAgents = 10 }
    val service = DispatchService(config(), state, linear, CollectingAgentRunner(), cache, noopLogger(), "p")

    service.handleClarification("issue-1", "Question")

    assertThat(linear.assignedIssueId).isNull()
}
```

- [ ] **Step 4: Add handleClarification to DispatchService**

```kotlin
suspend fun handleClarification(issueId: String, question: String) {
    logger.info("clarification_start", mapOf("issue_id" to issueId))
    try {
        linear.createComment(issueId, "Agent needs clarification:\n\n$question")
    } catch (e: Exception) {
        logger.warn("clarification_comment_failed", mapOf("issue_id" to issueId),
            "error" to (e.message ?: "unknown"))
    }
    try {
        val blockedStateId = linear.resolveStateId(projectSlug, config.blockedState)
        if (blockedStateId != null) {
            val creator = try { linear.fetchIssueCreator(issueId) } catch (_: Exception) { null }
            val targetUserId = when {
                creator != null && !creator.isBot -> creator.id
                config.projectAdmin != null -> config.projectAdmin
                else -> null
            }
            linear.updateIssueState(issueId, blockedStateId)
            if (targetUserId != null) {
                linear.updateIssueAssignee(issueId, targetUserId)
            }
        } else {
            logger.warn("clarification_state_not_found", mapOf(
                "issue_id" to issueId, "target_state" to config.blockedState))
        }
    } catch (e: Exception) {
        logger.warn("clarification_update_failed", mapOf("issue_id" to issueId),
            "error" to (e.message ?: "unknown"))
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :koncerto-orchestrator:test --tests "*DispatchServiceTest*" --rerun-tasks`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt
git commit -m "feat: add handleClarification to DispatchService"
```

---

### Task 7: Route ClarificationRequested in Orchestrator

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Modify: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt` (update fake LinearClients + test)

- [ ] **Step 1: Update 3 fake LinearClients in OrchestratorTest.kt**

Add `createComment`, `updateIssueAssignee`, `fetchIssueCreator` to:

**`FakeLinearClient`:**
```kotlin
override suspend fun createComment(issueId: String, body: String): String? = null
override suspend fun updateIssueAssignee(issueId: String, userId: String) {}
override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
```

**`FakeLinearClientWithStates`:**
```kotlin
override suspend fun createComment(issueId: String, body: String): String? = null
override suspend fun updateIssueAssignee(issueId: String, userId: String) {}
override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
```

**`FakeLinearClientThrowing`:**
```kotlin
override suspend fun createComment(issueId: String, body: String): String? =
    throw RuntimeException("API down")
override suspend fun updateIssueAssignee(issueId: String, userId: String) =
    throw RuntimeException("API down")
override suspend fun fetchIssueCreator(issueId: String): UserRef? =
    throw RuntimeException("API down")
```

Add import:
```kotlin
import com.flexsentlabs.koncerto.core.model.UserRef
```

- [ ] **Step 2: Update sampleConfig in OrchestratorTest**

Add `blockedState` and `projectAdmin`:

```kotlin
blockedState = "Blocked",
projectAdmin = null,
```

- [ ] **Step 3: Write test for ClarificationRequested routing**

```kotlin
// In OrchestratorTest — add test
@Test
fun `handleAgentEvent routes ClarificationRequested`() = runBlocking {
    val linear = FakeLinearClient(emptyList())
    val orch = makeOrchestrator(linear = linear)

    val event = AgentEvent.ClarificationRequested(
        issueId = "issue-1", question = "What should I do?", pid = null
    )
    orch.handleAgentEvent(event)

    // Verify no crash — DispatchService.handleClarification handles via linear
    // In a real scenario it creates comment + state change
    assertThat(orch).isNotNull()
}
```

- [ ] **Step 4: Route ClarificationRequested in Orchestrator.handleAgentEvent**

Add a new branch in the `when` block:

```kotlin
is AgentEvent.ClarificationRequested -> {
    logger.info("clarification_received", mapOf(
        "issue_id" to event.issueId))
    try {
        dispatchService.handleClarification(event.issueId, event.question)
    } catch (e: Exception) {
        logger.failure("clarification_handling_failed", mapOf(
            "issue_id" to event.issueId), e)
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :koncerto-orchestrator:test --tests "*OrchestratorTest*" --rerun-tasks`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt
git commit -m "feat: route ClarificationRequested events to DispatchService"
```

---

### Task 8: Update all remaining test fakes + build fixes

**Files:**
- Modify: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/ApiV1ControllerTest.kt`
- Modify: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt`

- [ ] **Step 1: Update ApiV1ControllerTest's ServiceConfig**

In `ApiV1ControllerTest.minimalConfig()`, add:

```kotlin
blockedState = "Blocked",
projectAdmin = null,
```

- [ ] **Step 2: Run all tests**

Run: `./gradlew build -x :koncerto-e2e:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Fix any compilation errors**

If any `LinearClient` implementations are missing in test files or production code, add the three new methods.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix: update all test fakes for new LinearClient methods"
```

---

### Task 9: Conventional commits in GitWorkflow

**Files:**
- Modify: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/GitWorkflow.kt`
- Test: `koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/GitWorkflowTest.kt`

- [ ] **Step 1: Write failing test for conventional commit prefix**

```kotlin
// In GitWorkflowTest.kt
@Test
fun `commit message uses feat prefix by default`() {
    val config = GitConfig(enabled = true, autoCommit = true)
    val workflow = GitWorkflow(config, noopLogger())
    repoDir.resolve("test.txt").writeText("content")
    workflow.commitAndPush(repoDir, "ABC-1", "Add login", labels = emptyList())
    val log = runGit("log", "--oneline", "-1")
    assertThat(log).contains("feat: ABC-1 Add login")
}

@Test
fun `commit message uses fix prefix when fix label present`() {
    val config = GitConfig(enabled = true, autoCommit = true)
    val workflow = GitWorkflow(config, noopLogger())
    repoDir.resolve("test.txt").writeText("content")
    workflow.commitAndPush(repoDir, "ABC-1", "Fix bug", labels = listOf("fix"))
    val log = runGit("log", "--oneline", "-1")
    assertThat(log).contains("fix: ABC-1 Fix bug")
}

@Test
fun `commit message uses feat prefix when feat label present`() {
    val config = GitConfig(enabled = true, autoCommit = true)
    val workflow = GitWorkflow(config, noopLogger())
    repoDir.resolve("test.txt").writeText("content")
    workflow.commitAndPush(repoDir, "ABC-1", "New feature", labels = listOf("feat"))
    val log = runGit("log", "--oneline", "-1")
    assertThat(log).contains("feat: ABC-1 New feature")
}
```

- [ ] **Step 2: Run tests to verify failure**

Run: `./gradlew :koncerto-workspace:test --tests "*GitWorkflowTest*" --rerun-tasks`
Expected: compilation errors — `commitAndPush` doesn't accept `labels`

- [ ] **Step 3: Update GitWorkflow to accept labels**

Change `commitAndPush` signature to accept optional labels:

```kotlin
fun commitAndPush(workspacePath: Path, issueIdentifier: String, title: String, labels: List<String> = emptyList()) {
```

Add a helper method:

```kotlin
private fun conventionalPrefix(labels: List<String>): String {
    if (labels.any { it.equals("fix", ignoreCase = true) || it.startsWith("fix:") }) return "fix"
    return "feat"
}
```

Update the commit message construction:
```kotlin
val prefix = conventionalPrefix(labels)
runGitSafe(workspacePath, "commit", "--allow-empty", "-m", "$prefix: $issueIdentifier $title")
```

- [ ] **Step 4: Update DefaultAgentRunner call site**

In `AgentRunner.kt`, pass `issue.labels` to `commitAndPush`:

```kotlin
gw.commitAndPush(workspace.path, issue.identifier, issue.title, issue.labels)
```

- [ ] **Step 5: Run all tests**

Run: `./gradlew :koncerto-workspace:test :koncerto-agent:test --rerun-tasks`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/GitWorkflow.kt koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/GitWorkflowTest.kt koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt
git commit -m "feat: add conventional commit prefix to GitWorkflow"
```

---

### Task 10: Update prompts and WORKFLOW.md

**Files:**
- Modify: `prompts/implement.md`
- Modify: `WORKFLOW.md`

- [ ] **Step 1: Update prompts/implement.md with TDD + conventional commits**

Replace the entire file:

```markdown
Implement the changes required for {{ issue.identifier }}.

Title: {{ issue.title }}
Description: {{ issue.description }}

## Process
1. **Understand** the requirements and design your approach
2. **Write tests first** (Red–Green–Refactor) — use the project's existing test framework
3. **Run tests** to confirm they fail (Red phase)
4. **Implement** the minimal code to make tests pass (Green phase)
5. **Refactor** while keeping tests green
6. **Commit** with a conventional commit message the system generates for you

Work in the current directory — the git branch is already set up. When done, your changes will be automatically committed and pushed, and a pull request will be created.

## If you need clarification
Write your question to `.koncerto/clarification.md` in the workspace. The system will post it to the Linear issue, mark it blocked, and assign it to the appropriate person.
```

- [ ] **Step 2: Update WORKFLOW.md**

Add to the `tracker` section:

```yaml
  blocked_state: "Blocked"
  project_admin: $LINEAR_USER_ID
```

- [ ] **Step 3: Commit**

```bash
git add prompts/implement.md WORKFLOW.md
git commit -m "docs: update prompts and WORKFLOW.md with TDD, conventional commits, clarifications"
```

---

### Task 11: Full build and verify

- [ ] **Step 1: Run full build**

Run: `./gradlew build -x :koncerto-e2e:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix any issues**

If compilation or test failures occur, fix them and re-run.

- [ ] **Step 3: Final commit (if needed)**

```bash
git add -A
git commit -m "fix: build fixes after full integration"
```

- [ ] **Step 4: Push**

```bash
git push
```
