# TDD, Conventional Commits & Human-in-the-Loop Clarifications

Date: 2026-06-09

## Overview

Three related workflow improvements for koncerto's agent pipeline:

1. **TDD by default** — coding agents write tests before implementation
2. **Conventional commits** — git commit messages follow the Conventional Commits spec
3. **Human-in-the-loop clarifications** — agents signal blockers by writing a clarification file; orchestrator updates Linear (comment + blocked state + assignee). Re-dispatch when unblocked.

---

## 1. TDD by Default

### Prompt change

`prompts/implement.md` updated to include:

- Write tests before implementation code (Red–Green–Refactor)
- Run tests after each change cycle
- Use the project's existing test framework (detected from build files)
- If tests don't exist yet, create the test infrastructure first
- Commit after green phase with conventional commit message

No code changes needed — purely a prompt content change.

---

## 2. Conventional Commits

### Commit message format

`GitWorkflow.commitAndPush()` constructs messages as:

```
<type>: <identifier> <title>
```

Where `<type>` is inferred:
- If the issue has a `fix:` label → `fix`
- If the issue has a `feat:` label → `feat`  
- Default → `feat` (most Linear issues represent features)

Example:
```
feat: ABC-123 Add user login screen
fix: ABC-124 Fix NPE on empty state
```

### Files changed

- `koncerto-workspace/src/main/kotlin/.../GitWorkflow.kt` — update `commitAndPush()` to accept `labels` and construct the conventional prefix
- `prompts/implement.md` — tell the agent to write meaningful commit bodies

---

## 3. Clarification Workflow

### Flow

```
Agent (in subprocess)
  └─ writes .koncerto/clarification.md in workspace
     └─ DefaultAgentRunner detects file after runtime.stop()
        └─ emits ClarificationRequested(issueId, question) on AgentEvent flow
           └─ Orchestrator.handleAgentEvent()
              └─ dispatchService.handleClarification(issueId, question)
                 ├─ 1. linear.createComment(issueId, question)
                 ├─ 2. linear.updateIssueState(issueId, blockedStateId)
                 └─ 3. linear.updateIssueAssignee(issueId, targetUserId)
```

### New LinearClient API

Three new methods on the `LinearClient` interface:

```kotlin
suspend fun createComment(issueId: String, body: String): String? // returns comment ID
suspend fun updateIssueAssignee(issueId: String, userId: String)
suspend fun fetchIssueCreator(issueId: String): UserRef?
```

Supporting types:

```kotlin
data class UserRef(
    val id: String,
    val displayName: String,
    val isBot: Boolean
)
```

### New GraphQL operations

**commentCreate mutation:**
```graphql
mutation CommentCreate($issueId: String!, $body: String!) {
  commentCreate(input: { issueId: $issueId, body: $body }) { success comment { id } }
}
```

**issueUpdate with assignee:**
```graphql
mutation IssueUpdate($id: String!, $stateId: String!, $assigneeId: String) {
  issueUpdate(id: $id, input: { stateId: $stateId, assigneeId: $assigneeId }) { success }
}
```

**issue with creator query:**
```graphql
query IssueWithCreator($id: String!) {
  issue(id: $id) {
    id identifier title description state { name }
    creator { id displayName isBot }
  }
}
```

### New AgentEvent

```kotlin
data class ClarificationRequested(
    val issueId: String,
    val question: String,
    override val timestamp: Instant = Instant.now(),
    override val pid: Long? = null
) : AgentEvent()
```

### Detection in AgentRunner

In `DefaultAgentRunner.run()`, after `runtime.stop()` and `hooks.afterRun`, check for `.koncerto/clarification.md`:

```kotlin
val clarificationFile = workspace.path.resolve(".koncerto/clarification.md")
if (clarificationFile.toFile().exists()) {
    val question = clarificationFile.readText().trim()
    clarificationFile.toFile().delete()
    eventFlow.tryEmit(AgentEvent.ClarificationRequested(issue.id, question))
}
```

The agent run result is still `Success` (blocking on clarification is not a failure).

### New DispatchService method

```kotlin
suspend fun handleClarification(issueId: String, question: String) {
    // 1. Add comment to Linear
    linear.createComment(issueId, "Agent needs clarification:\n\n$question")
    // 2. Resolve blocked state ID
    val blockedStateId = linear.resolveStateId(projectSlug, config.blockedState)
    if (blockedStateId != null) {
        // 3. Determine assignee
        val creator = linear.fetchIssueCreator(issueId)
        val targetUserId = if (creator != null && !creator.isBot) creator.id
                           else config.projectAdmin
        // 4. Update state + assignee
        linear.updateIssueState(issueId, blockedStateId)
        if (targetUserId != null) {
            linear.updateIssueAssignee(issueId, targetUserId)
        }
    }
}
```

### New config fields

Added to `ServiceConfig`:

```yaml
tracker:
  blocked_state: "Blocked"          # state to transition to when blocked
  project_admin: $LINEAR_USER_ID    # fallback assignee (env var supported)
```

### Re-dispatch when unblocked

No code changes needed for re-dispatch. When the user moves the issue back to "Todo" (or any `active_state`):
1. `Blocked` is NOT in `active_states` → issue drops out of polling
2. User answers the clarifying comment on Linear
3. User moves issue back to "Todo" (or "In Progress")
4. Next `tick()` → `fetchCandidateIssues()` picks it up
5. `dispatch()` runs it again — the agent prompt includes the issue description + any comments

### Issue model update

The `Issue` data class gains a `creator` field:

```kotlin
data class Issue(
    ...
    val creator: UserRef? = null
)
```

This is populated from the `issueWithCreator` query used in `fetchIssueCreator()`. Existing queries that don't request creator will get `null`.

---

## Files Changed Summary

| File | Change |
|------|--------|
| `prompts/implement.md` | Add TDD + conventional commit instructions |
| `koncerto-core/.../Issue.kt` | Add `creator: UserRef?` field |
| `koncerto-core/.../ServiceConfig.kt` | Add `blockedState`, `projectAdmin` fields |
| `koncerto-agent/.../AgentEvent.kt` | Add `ClarificationRequested` event |
| `koncerto-agent/.../AgentRunner.kt` | Detect `.koncerto/clarification.md` after runtime stops |
| `koncerto-linear/.../LinearClient.kt` | Add `createComment`, `updateIssueAssignee`, `fetchIssueCreator` + GraphQL queries/mutations |
| `koncerto-linear/.../IssueMapper.kt` | Parse `creator` from GraphQL response |
| `koncerto-orchestrator/.../DispatchService.kt` | Add `handleClarification()` |
| `koncerto-orchestrator/.../Orchestrator.kt` | Route `ClarificationRequested` to `dispatchService.handleClarification()` |
| `koncerto-workspace/.../GitWorkflow.kt` | Conventional commit prefix from issue labels |
| `WORKFLOW.md` | Add `blocked_state`, `project_admin` config |
| Various test files | New tests for each changed component |

## Testing Strategy

- **TDD prompt**: no automated test (prompt text change only)
- **Conventional commits**: update `GitWorkflowTest` to verify label-based prefix
- **Clarification flow**:
  - Unit test `AgentRunner` detection with fake clarification file
  - Unit test `DispatchService.handleClarification()` with mock LinearClient
  - Unit test `Orchestrator` event routing
- **LinearClient**: unit tests for new GraphQL queries/mutations with `FakeGraphqlClient`
- **Issue model**: serialization test for `creator` field
- **Config parsing**: tests for `blocked_state`, `project_admin` defaults
