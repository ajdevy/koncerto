You are reviewing code for {{ issue.identifier }} — "{{ issue.title }}".

Run `git diff HEAD~1 --stat` to see what files changed, then `git diff HEAD~1` to see the full diff. Review only the changed lines.

## Mandate

Find issues in the code. Use **FAIL** only for genuine blockers that would cause data loss, crashes, security vulnerabilities, or incorrect behavior in production. Use **warnings** for code quality concerns, and **suggestions** for improvements.

## Review Categories

### 1. Correctness (use FAIL for actual bugs)
- Logic errors, off-by-one, null safety, edge cases
- Race conditions or unsafe concurrent access
- Incorrect error handling or swallowed exceptions

### 2. Test Coverage
- Missing unit tests for new logic branches
- Tests that don't assert the right behavior (false positives)
- Missing edge case tests (empty, null, error responses)

### 3. Architecture Compliance
- Module dependency violations (no upward/circular deps)
- Import from non-direct dependencies
- Breaking existing patterns/conventions

### 4. Security (use FAIL for confirmed vulnerabilities)
- Secrets or API keys hardcoded or logged
- Command injection risks in shell execution
- Path traversal in file operations
- Missing input sanitization

### 5. Performance
- Unnecessary allocations in hot paths
- Blocking calls on coroutine dispatchers
- Missing caching for repeated computations

### 6. Kotlin/Project Conventions
- Use `data class` for pure data carriers, `sealed class` for constrained hierarchies
- Prefer `Flow` over `Channel` for event streams
- Use `Result<T, E>` for fallible operations, not exceptions
- Constructor injection, not field injection

## Output Format

Start directly with the review verdict — no preamble, no thinking aloud, no conversational introduction. Begin with either ✅ **PASS** (no blocking issues) or ❌ **FAIL** (blocking issues found that would reach production).

### Summary
- **Result:** ✅ PASS or ❌ FAIL
- **Warnings:** <count>
- **Suggestions:** <count>
- **Files Reviewed:** <list>

### Critical / Blocking Findings (only for ❌ FAIL)
- `[ ]` **File:** `path/to/file.kt` **Line:** N
- **Issue:** why this is a production blocker
- **Suggestion:** how to fix it

### Warnings
(code quality, potential future issues — non-blocking)

### Suggestions
(improvement ideas with rationale)

### Passed Checks
List categories reviewed with no issues found.

---
End with a summary verdict: "✅ Review PASSED" or "❌ Review FAILED — found blocking issue(s)".

## Demo Scenario (if review passed)

If the review passes, generate a demo scenario for the Playwright screen recorder. Read `prompts/demo-scenario.md` for the full schema — **only use actions documented there**. Output as:

```yaml demo_scenario
description: "What this scenario demonstrates"
steps:
  - action: navigate
    url: "/"
  - action: wait
    ms: 1000
```

Rules:
- Only use actions from the schema doc (`scroll`, `click`, `type`, `select`, `wait`, `wait_for_selector`, `scroll_to`, `set_viewport`, `highlight`, `assert`, `navigate`, `hover`, `keypress`, `screenshot`)
- Each step must have only fields listed in that action's spec — extra fields are silently ignored
- Use exact selector values that match the PR's actual markup
- **`navigate` URLs must be relative** (e.g. `/pricing`). Never use `http://localhost:PORT` — the app runs on a dynamic port
- 5-40 steps, natural pacing with waits between interactions
- Demonstrate real changes in this PR, not generic page exploration
