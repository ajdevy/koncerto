# Advanced Orchestration Features Design

**Date:** 2026-06-09
**Status:** Draft
**Applies to:** Koncerto orchestrator

## Overview

This spec extends the Koncerto orchestrator with four capabilities that unlock sophisticated issue workflows:

1. **Parallel Execution Groups** — Auto-discover independent subgraphs in the dependency DAG and dispatch them concurrently
2. **Blocker State Tracking** — Real-time detection of blocker completion via poll-time reconciliation, enabling automatic unblocking
3. **Workflow Chaining** — Automatic follow-up issue creation when an issue transitions to a terminal state
4. **Agent Specialization Routing** — Configurable rules that route issues to specific agent configs based on labels, state, or priority

---

## 1. Parallel Execution Groups

### 1.1 Approach: Auto-Frontier

Instead of requiring explicit batch labels, the orchestrator builds a dependency graph from `blockedBy` relations on candidate issues, computes the "frontier" (issues whose blockers are all resolved or absent from the candidate set), and dispatches from that frontier.

### 1.2 Dependency Graph

```kotlin
data class DependencyGraph(
    val nodes: Map<String, Issue>,           // issueId → Issue
    val edges: Map<String, Set<String>>,     // issueId → set of blocker issue IDs
    val frontier: List<Issue>                // issues with no unresolved blockers
)
```

### 1.3 Frontier Rules

An issue is on the frontier when ALL of its `blockedBy` entries satisfy one of:
- The blocker has `id == null` (unlinked blocker reference — skip)
- The blocker ID is NOT in the candidate set (already done, moved to another project, or external)
- The blocker IS in the candidate set AND its state is terminal

Unlinked blockers or blockers absent from the candidate set are treated as "resolved" (no dependency enforcement).

### 1.4 Dispatching

- `fetchAndDispatch()` builds the graph from candidates → computes frontier → sorts by priority → dispatches up to available slots
- When an issue completes and transitions to terminal, subsequent polls will re-evaluate the graph and new frontier issues may become available
- No new API calls needed beyond the existing `fetchCandidateIssues`

### 1.5 Testing

- Graph with no blockers → all candidates on frontier
- Chain A→B→C → only A on frontier
- Diamond A→(B,C)→D → A on frontier; B,C on frontier after A done; D on frontier after B,C done
- Unlinked blocker → treated as resolved, issue on frontier
- Mixed: some blockers in candidate set, some external → partial frontier

---

## 2. Blocker State Tracking

### 2.1 Approach: Poll-Time Enhancement (2A)

Extend the existing `reconcile()` method to update blocker awareness. When reconciliation detects that a running/claimed issue's blocker has reached a terminal state, emit a log event and let the next poll pick it up.

### 2.2 Changes to reconcile()

Current `reconcile()`:
- Fetches states for all running issues
- Removes issues that reached terminal or left active states

Extended `reconcile()`:
- Same fetch + cleanup
- NEW: After cleanup, scan `state.claimed` and `state.running` for issues whose `blockedBy` entries have all resolved
- Log `unblocked` for those issues
- Also: remove entries from `state.blocked` set when their blockers resolve

### 2.3 RuntimeState Changes

```kotlin
// Add: track issues that are currently blocked (enhances existing `blocked` set)
val blocked: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

// Add: helper
fun markBlocked(issueId: String)
fun markUnblocked(issueId: String): Boolean
fun isBlocked(issueId: String): Boolean
```

### 2.4 Dispatch Logic Change

`isBlockedForTodo()` currently checks blockers at fetch time. Enhance to also check `state.blocked` set so that issues which were unblocked mid-cycle get picked up.

### 2.5 Testing

- Issue blocked by another → not dispatched
- Blocker reaches terminal → reconcile unblocks → next poll dispatches
- Multiple blockers → all must resolve
- Chain unblock → downstream issues cascade

---

## 3. Workflow Chaining

### 3.1 Approach

Extend `StageAgentConfig` with a `followUp` field. When an issue transitions to the `onCompleteState` and a `followUp` config exists, the orchestrator creates a new issue in Linear with a rendered title, appropriate state, labels, and a link relationship.

### 3.2 Config Model

```kotlin
@Serializable
data class StageAgentConfig(
    // ...existing fields...
    val followUp: FollowUpConfig? = null
)

@Serializable
data class FollowUpConfig(
    val titleTemplate: String,       // Required. "PR Review: {{ issue.title }}"
    val state: String,               // Required. Initial state for follow-up
    val descriptionTemplate: String? = null, // Optional. Rendered description
    val labels: List<String> = emptyList(),
    val linkType: String = "blocks", // "blocks", "relates", "duplicates"
    val assignee: String? = null,    // Linear user ID, "creator", or null for unassigned
    val agent: String? = null        // Optional routing to specific agent
)
```

### 3.3 Template Rendering

Available template variables:
- `{{ issue.id }}` — Linear issue ID
- `{{ issue.identifier }}` — e.g. "ENG-123"
- `{{ issue.title }}` — Original issue title
- `{{ issue.url }}` — Link to original issue
- `{{ issue.state }}` — State when chaining triggered
- `{{ issue.labels }}` — Comma-separated labels
- `{{ now }}` — Current timestamp (ISO)

### 3.4 Concrete Scenarios

| Scenario | Trigger State | Follow-up Title | State | Link | Agent |
|----------|--------------|-----------------|-------|------|-------|
| Feature → PR Review | Done | "PR Review: Implement auth" | Todo | blocks | codex |
| Bug → Verification | Done | "Verify fix: Null pointer crash" | In Progress | relates | — |
| Deploy → Monitor | Deployed | "Monitor: Deploy v2.1.0" | In Progress | relates | — |
| Epic → Sub-task | In Progress | "Sub-task: [title]" | Todo | blocks | — |

### 3.5 Implementation Flow

In `DispatchService.transitionOnComplete()`:
1. Check if `stageConfig.followUp != null`
2. Render `titleTemplate` using issue data
3. Call `linear.createIssue(projectSlug, title, state, description, labels)`
4. Call `linear.createLink(sourceIssueId, newIssueId, linkType)` if supported
5. Log the chain creation event

### 3.6 Linear API

Add to `LinearClient`:
```kotlin
suspend fun createIssue(
    projectSlug: String,
    title: String,
    state: String,
    description: String? = null,
    labels: List<String> = emptyList()
): Issue?

suspend fun createLink(
    sourceIssueId: String,
    targetIssueId: String,
    type: String  // "blocks", "relates", "duplicates"
): Boolean
```

### 3.7 Testing

- Follow-up created when `onCompleteState` reached
- Template rendering with all vars
- Missing `state` in Linear → warn log, no crash
- `linkType` invalid → warn log, issue created but unlinked
- Follow-up issue picked up by next poll if it matches active states

---

## 4. Agent Specialization Routing

### 4.1 Approach

Add a `routingRules` field to `AgentProjectConfig`. Rules are evaluated in priority order. First match wins; if no rule matches, fall back to existing `resolveAgent()` logic.

### 4.2 Config Model

```kotlin
@Serializable
data class AgentProjectConfig(
    // ...existing fields...
    val routingRules: List<RoutingRule> = emptyList()
)

@Serializable
data class RoutingRule(
    val ifLabel: String? = null,
    val ifLabelPrefix: String? = null,
    val ifState: String? = null,
    val ifPriority: Int? = null,
    val ifPriorityMax: Int? = null,
    val useAgent: String,
    val priority: Int = 0
)
```

### 4.3 Evaluation Order

1. Sort rules by `priority` descending (higher first)
2. For each rule, check all non-null conditions:
   - `ifLabel`: issue.labels contains this exact label (case-insensitive)
   - `ifLabelPrefix`: any label starts with this prefix
   - `ifState`: issue.normalizedState matches (case-insensitive)
   - `ifPriority`: issue.priority == this value
   - `ifPriorityMax`: issue.priority <= this value
3. First matching rule → resolve agent by `useAgent` key in `agents` map
4. No match → existing fallback logic

### 4.4 YAML Config Example

```yaml
agent:
  kind: opencode
  agents:
    codex-gpt4:
      kind: codex
      model: gpt-4
    opencode-claude:
      kind: opencode
      model: claude-sonnet-4
    debug-specialist:
      kind: codex
      model: gpt-4
      command: codex app-server --debug
  routing_rules:
    - if_label: "frontend"
      use_agent: "codex-gpt4"
      priority: 10
    - if_label_prefix: "backend:"
      use_agent: "opencode-claude"
      priority: 5
    - if_label: "bug"
      if_priority_max: 2
      use_agent: "debug-specialist"
      priority: 8
```

### 4.5 Integration with Existing resolveAgent()

The `resolveAgent()` method currently checks:
1. Stage config → agent provider
2. Label `agent:` prefix
3. Label `model:` prefix
4. Default kind/command/model

Add routing rules as step 0 (before all other checks). Return early if matching rule → resolved agent.

### 4.6 Parsing

Add `parseRoutingRules()` to `ServiceConfig`:

```kotlin
internal fun parseRoutingRules(agentMap: Map<*, *>?): List<RoutingRule> {
    val raw = agentMap?.get("routing_rules") as? List<*> ?: return emptyList()
    return raw.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val useAgent = (map["use_agent"] as? String) ?: return@mapNotNull null
        RoutingRule(
            ifLabel = map["if_label"] as? String,
            ifLabelPrefix = map["if_label_prefix"] as? String,
            ifState = map["if_state"] as? String,
            ifPriority = (map["if_priority"] as? Number)?.toInt(),
            ifPriorityMax = (map["if_priority_max"] as? Number)?.toInt(),
            useAgent = useAgent,
            priority = (map["priority"] as? Number)?.toInt() ?: 0
        )
    }.sortedByDescending { it.priority }
}
```

### 4.7 Testing

- Rule with ifLabel matches → correct agent used
- Multiple rules → highest priority wins
- No matching rule → existing fallback
- ifLabelPrefix matches any label
- ifState + ifPriority combinators
- Missing useAgent key in agents map → warn log, fallback to default
- Routing runs before stage config agent provider

---

## 5. Implementation Order

1. **Blocker State Tracking** (smallest change, enables better parallel dispatch)
2. **Parallel Execution Groups** (builds on blocker tracking for frontier computation)
3. **Agent Specialization Routing** (self-contained config change)
4. **Workflow Chaining** (requires LinearClient additions)

Each feature is independent — they can be implemented in any order.

---

## 6. Out of Scope

- Explicit batch labels (auto-frontier covers this)
- Event-driven blocker tracking (keeping poll-time for now)
- Cross-project dependency resolution (blocker IDs from other projects treated as resolved)
- Workflow chaining → automated PR creation (Linear issue only)
- Visual dependency graph in dashboard
