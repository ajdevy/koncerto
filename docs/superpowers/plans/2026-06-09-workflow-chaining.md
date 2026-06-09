# Workflow Chaining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically create follow-up issues when an issue transitions to its onCompleteState, with configurable templates, states, labels, and link relations.

**Architecture:** `FollowUpConfig` on `StageAgentConfig`; template rendering via simple string replacement; `createIssue` and `createLink` on `LinearClient`; execution in `transitionOnComplete()`.

**Tech Stack:** Kotlin, JUnit5, kotlinx.serialization, Linear GraphQL API

---

### Task 1: FollowUpConfig Model and Parsing

**Files:**
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`
- Test: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun `parseStageConfig with followUp`() {
    val config = ServiceConfig.parseStages(mapOf(
        "stages" to mapOf(
        "in_progress" to mapOf(
            "prompt" to "Do work",
            "on_complete_state" to "Done",
            "follow_up" to mapOf(
                "title_template" to "PR Review: {{ issue.title }}",
                "state" to "Todo",
                "labels" to listOf("pr-review", "auto"),
                "link_type" to "blocks",
                "assignee" to "creator"
            )
        ))
    ))
    val stage = config["in_progress"]!!
    assertNotNull(stage.followUp)
    assertEquals("PR Review: {{ issue.title }}", stage.followUp!!.titleTemplate)
    assertEquals("Todo", stage.followUp!!.state)
    assertEquals(listOf("pr-review", "auto"), stage.followUp!!.labels)
    assertEquals("blocks", stage.followUp!!.linkType)
    assertEquals("creator", stage.followUp!!.assignee)
}

@Test
fun `parseStageConfig without followUp`() {
    val config = ServiceConfig.parseStages(mapOf(
        "stages" to mapOf(
        "in_progress" to mapOf(
            "prompt" to "Do work",
            "on_complete_state" to "Done"
        ))
    ))
    assertNull(config["in_progress"]!!.followUp)
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :koncerto-core:test --tests "*ServiceConfigTest*"
```
Expected: FAIL

- [ ] **Step 3: Add FollowUpConfig and parsing**

In `ProjectConfig.kt`:

```kotlin
@kotlinx.serialization.Serializable
data class FollowUpConfig(
    val titleTemplate: String,
    val state: String,
    val descriptionTemplate: String? = null,
    val labels: List<String> = emptyList(),
    val linkType: String = "blocks",
    val assignee: String? = null,
    val agent: String? = null
)
```

Add to `StageAgentConfig`:

```kotlin
val followUp: FollowUpConfig? = null
```

In `ServiceConfig.parseStages()`, parse `follow_up`:

```kotlin
val followUp = (stageMap["follow_up"] as? Map<*, *>)?.let { f ->
    FollowUpConfig(
        titleTemplate = (f["title_template"] as? String) ?: return@let null,
        state = (f["state"] as? String) ?: return@let null,
        descriptionTemplate = f["description_template"] as? String,
        labels = (f["labels"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        linkType = (f["link_type"] as? String) ?: "blocks",
        assignee = f["assignee"] as? String,
        agent = f["agent"] as? String
    )
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :koncerto-core:test --tests "*ServiceConfigTest*"
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt
git commit -m "feat: add FollowUpConfig model and parsing"
```

---

### Task 2: Linear Client Extensions

**Files:**
- Modify: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearClient.kt`
- Modify: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearGraphQLClient.kt`
- Test: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearClientTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun `createIssue returns issue on success`() = runTest {
    coEvery { graphQLClient.executeQuery(any(), any()) } returns createIssueSuccessResponse()
    val result = linearClient.createIssue("test-project", "Test Issue", "Todo")
    assertNotNull(result)
    assertEquals("Test Issue", result!!.title)
}

@Test
fun `createIssue returns null on API error`() = runTest {
    coEvery { graphQLClient.executeQuery(any(), any()) } throws RuntimeException("API error")
    val result = linearClient.createIssue("test-project", "Test Issue", "Todo")
    assertNull(result)
}

@Test
fun `createLink returns true on success`() = runTest {
    coEvery { graphQLClient.executeQuery(any(), any()) } returns createLinkSuccessResponse()
    val result = linearClient.createLink("src-1", "tgt-1", "blocks")
    assertTrue(result)
}

@Test
fun `createLink returns false on API error`() = runTest {
    coEvery { graphQLClient.executeQuery(any(), any()) } throws RuntimeException("API error")
    val result = linearClient.createLink("src-1", "tgt-1", "blocks")
    assertFalse(result)
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :koncerto-linear:test --tests "*LinearClientTest*createIssue*"
```
Expected: FAIL

- [ ] **Step 3: Add createIssue and createLink to LinearClient**

Add to `LinearClient.kt`:

```kotlin
suspend fun createIssue(
    projectSlug: String,
    title: String,
    state: String,
    description: String? = null,
    labels: List<String> = emptyList()
): Issue? {
    return try {
        val teamId = resolveTeamId(projectSlug) ?: return null
        val stateId = resolveStateId(projectSlug, state)
        val labelIds = resolveLabelIds(labels)
        val mutation = buildCreateIssueMutation(teamId, title, description, stateId, labelIds)
        val response = graphQLClient.executeQuery(mutation, emptyMap())
        parseCreatedIssue(response)
    } catch (e: Exception) {
        logger.warn("create_issue_failed", mapOf("project_slug" to projectSlug, "title" to title))
        null
    }
}

suspend fun createLink(
    sourceIssueId: String,
    targetIssueId: String,
    type: String
): Boolean {
    return try {
        val mutation = buildCreateLinkMutation(sourceIssueId, targetIssueId, type)
        val response = graphQLClient.executeQuery(mutation, emptyMap())
        response["data"]?.jsonObject?.get("issueRelationCreate") != null
    } catch (e: Exception) {
        logger.warn("create_link_failed", mapOf("source" to sourceIssueId, "target" to targetIssueId))
        false
    }
}
```

Add helper methods to `LinearGraphQLClient.kt`:

```kotlin
fun buildCreateIssueMutation(
    teamId: String, title: String, description: String?,
    stateId: String?, labelIds: List<String>
): String = """
    mutation {
        issueCreate(input: {
            teamId: "$teamId",
            title: "${title.replace("\"", "\\\"")}",
            ${if (description != null) "description: \"${description.replace("\"", "\\\"")}\"," else ""}
            ${if (stateId != null) "stateId: \"$stateId\"," else ""}
            ${if (labelIds.isNotEmpty()) "labelIds: [${labelIds.joinToString(", ") { "\"$it\"" }}]," else ""}
        }) {
            success
            issue {
                id
                identifier
                title
                state { name }
            }
        }
    }
""".trimIndent()

fun buildCreateLinkMutation(sourceId: String, targetId: String, type: String): String = """
    mutation {
        issueRelationCreate(issue: {id: "$sourceId"}, relatedIssueId: "$targetId", type: $type) {
            success
        }
    }
""".trimIndent()
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :koncerto-linear:test
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearClient.kt koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearGraphQLClient.kt koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearClientTest.kt
git commit -m "feat: add createIssue and createLink to LinearClient"
```

---

### Task 3: Follow-Up Template Rendering

**Files:**
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/FollowUpRenderer.kt`
- Test: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/FollowUpRendererTest.kt`

- [ ] **Step 1: Write failing tests**

Create `FollowUpRendererTest.kt`:

```kotlin
package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.Issue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FollowUpRendererTest {

    private val issue = Issue(
        id = "abc-123", identifier = "ENG-42", title = "Implement auth",
        description = null, priority = 1, state = "In Progress",
        branchName = null, url = "https://linear.app/eng/issue/ENG-42",
        labels = listOf("frontend", "auth"), blockedBy = emptyList(),
        createdAt = null, updatedAt = null
    )

    @Test
    fun `renders issue title`() {
        val result = FollowUpRenderer.render("PR Review: {{ issue.title }}", issue)
        assertEquals("PR Review: Implement auth", result)
    }

    @Test
    fun `renders issue identifier`() {
        val result = FollowUpRenderer.render("Verify: {{ issue.identifier }}", issue)
        assertEquals("Verify: ENG-42", result)
    }

    @Test
    fun `renders issue url`() {
        val result = FollowUpRenderer.render("Review: {{ issue.url }}", issue)
        assertEquals("Review: https://linear.app/eng/issue/ENG-42", result)
    }

    @Test
    fun `renders issue labels as csv`() {
        val result = FollowUpRenderer.render("Labels: {{ issue.labels }}", issue)
        assertEquals("Labels: frontend, auth", result)
    }

    @Test
    fun `leaves unknown variables as-is`() {
        val result = FollowUpRenderer.render("Hello {{ unknown }}", issue)
        assertEquals("Hello {{ unknown }}", result)
    }

    @Test
    fun `renders now timestamp`() {
        val result = FollowUpRenderer.render("Created: {{ now }}", issue)
        assertTrue(result.startsWith("Created: 20"), "should contain current year: $result")
    }

    @Test
    fun `renders multiple variables in one template`() {
        val result = FollowUpRenderer.render("{{ issue.identifier }}: {{ issue.title }}", issue)
        assertEquals("ENG-42: Implement auth", result)
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :koncerto-orchestrator:test --tests "*FollowUpRendererTest*"
```
Expected: FAIL

- [ ] **Step 3: Write FollowUpRenderer**

Create `FollowUpRenderer.kt`:

```kotlin
package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.Issue
import java.time.Instant

object FollowUpRenderer {
    fun render(template: String, issue: Issue): String {
        var result = template
            .replace("{{ issue.id }}", issue.id)
            .replace("{{ issue.identifier }}", issue.identifier)
            .replace("{{ issue.title }}", issue.title)
            .replace("{{ issue.url }}", issue.url ?: "")
            .replace("{{ issue.state }}", issue.state)
            .replace("{{ issue.labels }}", issue.labels.joinToString(", "))
            .replace("{{ now }}", Instant.now().toString())
        return result
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :koncerto-orchestrator:test --tests "*FollowUpRendererTest*"
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/FollowUpRenderer.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/FollowUpRendererTest.kt
git commit -m "feat: add FollowUpRenderer for template-based issue creation"
```

---

### Task 4: Chain Execution in transitionOnComplete()

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Test: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun `transitionOnComplete creates follow-up issue when followUp config set`() = runTest {
    val issue = issue("a", "ENG-1", state = "In Progress")
    val stageConfig = StageAgentConfig(
        prompt = null, model = null, maxConcurrent = null,
        agentKind = null, command = null, onCompleteState = "Done",
        followUp = FollowUpConfig(
            titleTemplate = "PR Review: {{ issue.title }}",
            state = "Todo",
            linkType = "blocks",
            assignee = null
        )
    )
    val followUpIssue = issue("b", "ENG-2", state = "Todo")
    whenever(linear.resolveStateId(any(), any())).thenReturn("state-1")
    whenever(linear.updateIssueState(any(), any())).thenReturn(Unit)
    whenever(linear.createIssue(any(), any(), any(), any(), any())).thenReturn(followUpIssue)
    whenever(linear.createLink(any(), any(), any())).thenReturn(true)

    dispatchService.transitionOnComplete(issue, stageConfig)

    verify(linear).createIssue(eq("test-project"), eq("PR Review: ENG-1"), eq("Todo"), any(), any())
    verify(linear).createLink(eq("a"), eq("b"), eq("blocks"))
    verify(linear).updateIssueState(eq("a"), eq("state-1"))
}
```

- [ ] **Step 2: Run test**

```bash
./gradlew :koncerto-orchestrator:test --tests "*DispatchServiceTest*transitionOnComplete*"
```
Expected: FAIL

- [ ] **Step 3: Extend transitionOnComplete()**

In `DispatchService.kt`, update `transitionOnComplete()`:

```kotlin
private suspend fun transitionOnComplete(issue: Issue, stageConfig: StageAgentConfig?) {
    // Existing state transition
    val targetState = stageConfig?.onCompleteState ?: return
    try {
        val stateId = linear.resolveStateId(projectSlug, targetState)
        if (stateId == null) {
            logger.warn("state_not_found", mapOf(
                "issue_id" to issue.id,
                "target_state" to targetState
            ))
            return
        }
        linear.updateIssueState(issue.id, stateId)
        logger.info("state_transitioned", mapOf(
            "issue_id" to issue.id,
            "from_state" to issue.state,
            "to_state" to targetState
        ))
    } catch (e: Exception) {
        logger.failure("state_transition_failed", mapOf(
            "issue_id" to issue.id,
            "target_state" to targetState
        ), e)
        return
    }

    // Follow-up chaining
    val followUp = stageConfig?.followUp ?: return
    val renderedTitle = FollowUpRenderer.render(followUp.titleTemplate, issue)
    val renderedDescription = followUp.descriptionTemplate?.let { FollowUpRenderer.render(it, issue) }

    val created = linear.createIssue(
        projectSlug, renderedTitle, followUp.state,
        renderedDescription, followUp.labels
    )
    if (created == null) {
        logger.warn("follow_up_creation_failed", mapOf(
            "source_issue_id" to issue.id
        ))
        return
    }

    logger.info("follow_up_created", mapOf(
        "source_issue_id" to issue.id,
        "source_identifier" to issue.identifier,
        "follow_up_id" to created.id,
        "follow_up_identifier" to created.identifier
    ))

    if (followUp.linkType.isNotBlank()) {
        val linked = linear.createLink(issue.id, created.id, followUp.linkType)
        if (!linked) {
            logger.warn("follow_up_link_failed", mapOf(
                "source" to issue.id,
                "target" to created.id,
                "link_type" to followUp.linkType
            ))
        }
    }
}
```

- [ ] **Step 4: Run test**

```bash
./gradlew :koncerto-orchestrator:test --tests "*DispatchServiceTest*transitionOnComplete*"
```
Expected: PASS

- [ ] **Step 5: Run full tests**

```bash
./gradlew test
```
Expected: All pass

- [ ] **Step 6: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt
git commit -m "feat: add workflow chaining in transitionOnComplete"
```
