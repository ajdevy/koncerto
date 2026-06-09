# Blocker State Tracking & Parallel Execution Groups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add blocker state tracking to reconciliation and auto-group independent issues for parallel dispatch using dependency DAG frontier.

**Architecture:** Extend `reconcile()` to scan blocker states and auto-unblock issues; introduce `DependencyGraph` value object with frontier computation; replace `isBlockedForTodo()` in `fetchAndDispatch()` with frontier-based dispatch.

**Tech Stack:** Kotlin, JUnit5, kotlinx-coroutines, ConcurrentHashMap

---

### Task 1: Blocker State Tracking in reconcile()

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Test: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt`

- [ ] **Step 1: Write the failing test for blocker tracking in reconcile()**

Add to `OrchestratorTest.kt`:

```kotlin
@Test
fun `reconcile unblocks issue when blocker reaches terminal state`() = runTest {
    val linear = mock<LinearClient>()
    val blockerId = "blocker-1"
    val issueId = "issue-1"
    val blocker = Issue(
        id = blockerId, identifier = "ENG-1", title = "Blocker", state = "Done",
        priority = 1, labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )
    val issue = Issue(
        id = issueId, identifier = "ENG-2", title = "Blocked", state = "Todo",
        priority = 1, labels = emptyList(),
        blockedBy = listOf(BlockerRef(id = blockerId, identifier = "ENG-1", state = "In Progress")),
        createdAt = null, updatedAt = null
    )

    val state = RuntimeState()
    state.running[issueId] = RunningEntry(
        issue = issue, threadId = "t1", turnId = "tn1",
        startedAt = Instant.now(), lastCodexTimestamp = null
    )
    state.claimed.add(issueId)

    val pr = projectRuntime(linear = linear, state = state)
    // return "Done" for blocker
    whenever(linear.fetchIssueStatesByIds(any())).thenReturn(mapOf(blockerId to "Done"))

    orchestrator.reconcile(pr)

    assertFalse(state.running.containsKey(issueId))
    assertFalse(state.claimed.contains(issueId))
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :koncerto-orchestrator:test --tests "*OrchestratorTest*reconcile*blocker*" -x :koncerto-orchestrator:compileKotlin
```
Expected: FAIL

- [ ] **Step 3: Add blocker tracking logic to reconcile()**

In `Orchestrator.kt`, after the existing state cleanup loop in `reconcile()`, add:

```kotlin
// After existing cleanup, track blocker resolution
if (states.isNotEmpty()) {
    for ((id, entry) in state.running) {
        val unresolvedBlockers = entry.issue.blockedBy.filter { blocker ->
            val blockerId = blocker.id ?: return@filter false
            val blockerState = states[blockerId] ?: return@filter false
            pr.config.tracker.terminalStates.none { it.equals(blockerState, ignoreCase = true) }
        }
        if (unresolvedBlockers.isEmpty() && entry.issue.blockedBy.isNotEmpty()) {
            logger.info(
                "unblocked",
                mapOf("issue_id" to id, "issue_identifier" to entry.issue.identifier)
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :koncerto-orchestrator:test --tests "*OrchestratorTest*reconcile*blocker*"
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt
git commit -m "feat: add blocker state tracking to reconciliation"
```

---

### Task 2: DependencyGraph Data Class

**Files:**
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DependencyGraph.kt`
- Test: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DependencyGraphTest.kt`

- [ ] **Step 1: Write the failing test for DependencyGraph**

Create `DependencyGraphTest.kt`:

```kotlin
package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.BlockerRef
import com.anomaly.koncerto.core.model.Issue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyGraphTest {

    private fun issue(
        id: String, identifier: String = id, state: String = "Todo",
        blockedBy: List<BlockerRef> = emptyList(), priority: Int? = 1
    ) = Issue(
        id = id, identifier = identifier, title = identifier, state = state,
        priority = priority, labels = emptyList(), blockedBy = blockedBy,
        createdAt = null, updatedAt = null
    )

    @Test
    fun `frontier contains issues with no blockers`() {
        val a = issue("a", "ENG-1")
        val b = issue("b", "ENG-2")
        val graph = DependencyGraph.build(listOf(a, b))
        assertEquals(setOf("a", "b"), graph.frontier.map { it.id }.toSet())
    }

    @Test
    fun `chain dependency only first issue on frontier`() {
        val c = issue("c", "ENG-3", blockedBy = emptyList())
        val b = issue("b", "ENG-2", blockedBy = listOf(BlockerRef("c", "ENG-3", "In Progress")))
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "Todo")))
        val graph = DependencyGraph.build(listOf(a, b, c))
        assertEquals(listOf("c"), graph.frontier.map { it.id })
    }

    @Test
    fun `blocker absent from candidates is treated as resolved`() {
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("external", "EXT-1", "Done")))
        val graph = DependencyGraph.build(listOf(a))
        assertEquals(listOf("a"), graph.frontier.map { it.id })
    }

    @Test
    fun `blocker with null id is treated as resolved`() {
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef(null, null, null)))
        val graph = DependencyGraph.build(listOf(a))
        assertEquals(listOf("a"), graph.frontier.map { it.id })
    }

    @Test
    fun `blocker with terminal state is treated as resolved`() {
        val b = issue("b", "ENG-2", state = "Done")
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "Done")))
        val graph = DependencyGraph.build(listOf(a, b))
        assertEquals(listOf("a"), graph.frontier.map { it.id })
    }

    @Test
    fun `diamond dependency`() {
        val d = issue("d", "ENG-4")
        val c = issue("c", "ENG-3")
        val b = issue("b", "ENG-2", blockedBy = listOf(BlockerRef("d", "ENG-4", "Todo")))
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("d", "ENG-4", "Todo")))
        val graph = DependencyGraph.build(listOf(a, b, c, d))
        assertEquals(setOf("c", "d"), graph.frontier.map { it.id }.toSet())
    }

    @Test
    fun `all blocked returns empty frontier`() {
        val b = issue("b", "ENG-2")
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "In Progress")))
        val graph = DependencyGraph.build(listOf(a, b))
        assertTrue(graph.frontier.isEmpty())
    }

    @Test
    fun `frontier sorted by priority then identifier`() {
        val high = issue("h", "ENG-2", priority = 1)
        val low = issue("l", "ENG-1", priority = 5)
        val graph = DependencyGraph.build(listOf(low, high))
        assertEquals(listOf("h", "l"), graph.frontier.map { it.id })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :koncerto-orchestrator:test --tests "*DependencyGraphTest*"
```
Expected: FAIL

- [ ] **Step 3: Write minimal DependencyGraph implementation**

Create `DependencyGraph.kt`:

```kotlin
package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.model.Issue

data class DependencyGraph(
    val nodes: Map<String, Issue>,
    val edges: Map<String, Set<String>>,
    val frontier: List<Issue>
) {
    companion object {
        fun build(
            candidates: List<Issue>,
            terminalStates: List<String> = listOf("Closed", "Cancelled", "Canceled", "Duplicate", "Done")
        ): DependencyGraph {
            val nodes = candidates.associateBy { it.id }
            val edges = candidates.associate { issue ->
                val blockers = issue.blockedBy
                    .filter { ref -> ref.id != null }
                    .filter { ref -> nodes.containsKey(ref.id) }
                    .filter { ref ->
                        val blockerIssue = nodes[ref.id]!!
                        terminalStates.none { it.equals(blockerIssue.state, ignoreCase = true) }
                    }
                    .mapNotNull { it.id }
                    .toSet()
                issue.id to blockers
            }
            val frontier = candidates
                .filter { issue ->
                    val issueBlockers = edges[issue.id] ?: emptySet()
                    issueBlockers.isEmpty()
                }
                .sortedWith(
                    compareBy<Issue>({ it.priority ?: Int.MAX_VALUE })
                        .thenBy { it.identifier }
                )
            return DependencyGraph(nodes, edges, frontier)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :koncerto-orchestrator:test --tests "*DependencyGraphTest*"
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DependencyGraph.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DependencyGraphTest.kt
git commit -m "feat: add DependencyGraph with frontier computation"
```

---

### Task 3: Block-Aware Dispatch

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Test: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`

- [ ] **Step 1: Write failing test for frontier-based dispatch**

Add to `DispatchServiceTest.kt`:

```kotlin
@Test
fun `fetchAndDispatch uses frontier to skip blocked issues`() = runTest {
    val b = issue("b", "ENG-2")
    val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "In Progress")))
    whenever(linear.fetchCandidateIssues(any(), any())).thenReturn(listOf(a, b))
    val scope = CoroutineScope(Job() + dispatcher)
    dispatchService.fetchAndDispatch(scope)
    assertTrue(state.running.containsKey("b"), "b should be dispatched")
    assertFalse(state.running.containsKey("a"), "a should not be dispatched")
}

@Test
fun `fetchAndDispatch dispatches all frontier issues up to slot limit`() = runTest {
    val a = issue("a", "ENG-1")
    val b = issue("b", "ENG-2")
    val c = issue("c", "ENG-3")
    state.maxConcurrentAgents = 2
    whenever(linear.fetchCandidateIssues(any(), any())).thenReturn(listOf(a, b, c))
    val scope = CoroutineScope(Job() + dispatcher)
    dispatchService.fetchAndDispatch(scope)
    val runningIds = state.running.keys
    assertEquals(2, runningIds.size, "only 2 should run due to slot limit")
}

private fun issue(
    id: String, identifier: String = id, state: String = "Todo",
    blockedBy: List<BlockerRef> = emptyList(), priority: Int? = 1
) = Issue(
    id = id, identifier = identifier, title = identifier, state = state,
    priority = priority, labels = emptyList(), blockedBy = blockedBy,
    createdAt = null, updatedAt = null
)
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :koncerto-orchestrator:test --tests "*DispatchServiceTest*fetchAndDispatch*"
```
Expected: FAIL

- [ ] **Step 3: Update fetchAndDispatch() to use frontier**

In `DispatchService.kt`, replace the candidate filtering block with:

```kotlin
val candidates = try {
    linear.fetchCandidateIssues(projectSlug, projectConfig.tracker.activeStates)
} catch (e: Exception) {
    logger.failure("fetch_candidates_failed", emptyMap(), e)
    return
}

val graph = DependencyGraph.build(candidates, projectConfig.tracker.terminalStates)
val sorted = graph.frontier
    .filter { !state.running.containsKey(it.id) && it.id !in state.claimed }
    .filter { matchesRequiredLabels(it) }
    .filter { !isBlockedForTodo(it) }
    .sortedWith(
        compareBy<Issue>({ it.priority ?: Int.MAX_VALUE })
            .thenBy { it.createdAt ?: Instant.MAX }
            .thenBy { it.identifier }
    )

// Track blocked issues for dashboard
for (candidate in candidates) {
    if (candidate.id !in graph.frontier.map { it.id } && !state.running.containsKey(candidate.id) && candidate.id !in state.claimed) {
        state.blocked.add(candidate.id)
    }
}
for (frontierId in graph.frontier.map { it.id }) {
    state.blocked.remove(frontierId)
}
```

Also update `isBlockedForTodo()` to use `state.blocked`:

```kotlin
private fun isBlockedForTodo(issue: Issue): Boolean {
    if (!issue.normalizedState.equals("todo", ignoreCase = true)) return false
    return issue.id in state.blocked
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :koncerto-orchestrator:test --tests "*DispatchServiceTest*fetchAndDispatch*"
```
Expected: PASS

- [ ] **Step 5: Run all existing orchestration tests**

```bash
./gradlew :koncerto-orchestrator:test
```
Expected: All pass

- [ ] **Step 6: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt
git commit -m "feat: replace isBlockedForTodo with frontier-based dispatch"
```

---

### Task 4: Dashboard Blocker Visibility

**Files:**
- Modify: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`
- Modify: `koncerto-dashboard/src/main/resources/templates/dashboard.html`

- [ ] **Step 1: Read current API and dashboard files**

```bash
cat koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt
cat koncerto-dashboard/src/main/resources/templates/dashboard.html
```

- [ ] **Step 2: Add blocked state to API response**

In `ApiV1Controller.kt`, add blocked info to the state response:

```kotlin
data class IssueStatus(
    val identifier: String,
    val title: String,
    val priority: Int?,
    val state: String,
    val blocked: Boolean = false,
    val blockedBy: List<String> = emptyList()
)
```

Map blocked state from `RuntimeState.blocked`:

```kotlin
val projectRuntime = orchestrator.projects[slug] ?: return@mapNotNull null
val prj = projectRuntime
val blockedSet = prj.state.blocked
// ...in the mapping:
val isBlocked = blockedSet.contains(issue.id)
val blockers = prj.state.running[issue.id]?.issue?.blockedBy?.mapNotNull { it.identifier } ?: emptyList()
```

- [ ] **Step 3: Update dashboard HTML**

Add a blocked column or badge in the dashboard table. When `blocked` is true, show a badge with blocker identifiers.

- [ ] **Step 4: Build project**

```bash
./gradlew build -x test
```

- [ ] **Step 5: Commit**

```bash
git add koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt koncerto-dashboard/src/main/resources/templates/dashboard.html
git commit -m "feat: show blocked issues and blockers in dashboard"
```
