# Epic 16: Claude Code Post-Story Code Reviews

**Story Points:** 34
**Priority:** P1
**Status:** Complete

---

## Story 16.1: Code Review Prompt Template

**ID:** 16.1
**Title:** Code Review Prompt Template
**Points:** 3
**Priority:** P1

### User Story
- **As a** developer using Claude Code for reviews
- **I want** a comprehensive prompt template covering both general and Koncerto-specific standards
- **So that** every code review is consistent, thorough, and tailored to the project

### Acceptance Criteria
- [x] Prompt template created at `_bmad/review/prompt-template.md`
- [x] Template includes general categories: code correctness, security vulnerabilities, performance issues, error handling, edge cases, test coverage, API design, and readability
- [x] Template includes Koncerto-specific categories: Kotlin idioms (coroutines, flow, sealed classes), Spring Boot conventions (WebFlux, reactive types, dependency injection), existing module structure (orchestrator, dashboard, agent-runtime), and project-specific patterns from architecture.md
- [x] Template instructs Claude Code to produce findings categorized by severity: **CRITICAL** (blocks completion), **WARNING** (should fix), **SUGGESTION** (consider)
- [x] Template includes output format specification for both a structured markdown report and inline code annotations
- [x] Template references project-context.md for up-to-date project rules
- [x] Template versioned with frontmatter (version, last updated)

### Technical Notes
- Prompt should be self-contained so Claude Code needs no additional context beyond the prompt + code
- Use clear section delimiters so Claude Code reliably follows format
- Place in `_bmad/review/` since it's a process artifact, not application code

### Implementation
- Create: `_bmad/review/prompt-template.md`

---

## Story 16.2: Post-Story Review CLI Command

**ID:** 16.2
**Title:** Post-Story Review CLI Command
**Points:** 5
**Priority:** P1

### User Story
- **As a** developer finishing a coding story
- **I want** a single CLI command that runs Claude Code review on the changed code
- **So that** I don't have to manually construct the review invocation each time

### Acceptance Criteria
- [x] A script or Makefile target `make review` (or `./scripts/review.sh`) exists that:
  - Detects changed files relative to the base branch (git diff `main...HEAD --name-only`)
  - Loads the review prompt template from `_bmad/review/prompt-template.md`
  - Invokes Claude Code with the prompt, the diff, and the changed file paths
  - Captures stdout/stderr and routes to both report file and annotation parsing
- [x] Script accepts optional flags:
  - `--files <paths>` to specify exact files (overrides git diff detection)
  - `--output <dir>` to set custom report output directory (default `_bmad-output/review-reports/`)
  - `--verbose` to stream Claude Code output to terminal in real-time
  - `--model <model>` to specify which Claude model to use (default: claude-sonnet-4-20250514)
- [x] Script validates Claude Code CLI is installed (`which claude`) before running
- [x] Script exits with non-zero if Claude Code invocation fails
- [x] Error message is clear if Claude Code is not available or API key is missing
- [x] Script is idempotent — re-running on the same code produces a fresh review
- [x] Script respects `.gitignore` — only reviews tracked files, not generated artifacts

### Technical Notes
- Claude Code CLI invocation pattern: `cat prompt.md | claude -p "$(cat prompt.md) $(git diff)"` or use `claude --print` for non-interactive output with `--model` flag
- For non-interactive review, use `claude --print` with the prompt piped in
- Script should be POSIX-compatible shell (bash) for portability
- The `--model` flag: `claude --model claude-sonnet-4-20250514 --print` for example
- Store the script in `scripts/review.sh` (project already has `scripts/` conventions)

### Implementation
- Create: `scripts/review.sh`
- Create: `Makefile` target `review`

---

## Story 16.3: Structured Review Report

**ID:** 16.3
**Title:** Structured Review Report
**Points:** 5
**Priority:** P1

### User Story
- **As a** developer reviewing Claude Code's output
- **I want** a well-structured markdown report with findings organized by severity
- **So that** I can quickly understand what needs attention and what's advisory

### Acceptance Criteria
- [x] Report saved to `_bmad-output/review-reports/epic-{N}-story-{M}-{timestamp}.md`
- [x] Report includes header: story reference, date, files reviewed, Claude model used
- [x] Report has a **Summary** section: pass/fail status, total critical/warning/suggestion counts
- [x] Report has a **Critical Findings** section: each finding includes file path, line number, description of the issue, recommended fix, and a `[ ]` checkbox for tracking resolution
- [x] Report has a **Warnings** section: same format as critical but non-blocking
- [x] Report has a **Suggestions** section: improvement ideas with rationale
- [x] Report has a **Files Reviewed** section listing all files checked
- [x] Report has a **Passed Checks** section noting what categories were reviewed with no issues found
- [x] Report summary at the top shows a pass/fail badge: ✅ PASS or ❌ FAIL (FAIL if any criticals remain)
- [x] Report is self-contained and readable without needing the prompt template

### Technical Notes
- Report format is driven by the prompt template (16.1) — Claude Code outputs this structure
- Script (16.2) captures Claude Code's stdout and writes it to the report file
- Timestamp format: `YYYYMMDD-HHMMSS` for sortable filenames

### Implementation
- Output: `_bmad-output/review-reports/*.md`
- No code changes needed — report format is defined in the prompt template

---

## Story 16.4: Inline Code Annotations

**ID:** 16.4
**Title:** Inline Code Annotations
**Points:** 4
**Priority:** P1

### User Story
- **As a** developer fixing review findings
- **I want** Claude Code to annotate issues directly on the code
- **So that** I can see the problem and suggested fix right where the code is, without cross-referencing the report

### Acceptance Criteria
- [x] Claude Code adds annotations to source files using a consistent format: `// REVIEW [SEVERITY]: <message>  // suggested: <fix>`
- [x] Annotation format for Kotlin files: `// REVIEW [CRITICAL]: <description>` on the line above the issue, with indentation matching the code block
- [x] Annotation format for other file types using their comment syntax
- [x] Annotations are placed on the line immediately before the issue
- [x] Each annotation includes a unique reference ID matching the report (e.g., `[CRITICAL-001]`)
- [x] A cleanup command/flag exists: `scripts/review.sh --clean` removes all `// REVIEW` annotations from tracked files
- [x] Annotations do not break compilation or parsing (they're in comments)
- [x] The `--clean` flag restores files to their pre-annotation state (no leftover REVIEW markers)
- [x] Annotations are only added to files that were changed (not untouched files)

### Technical Notes
- Kotlin uses `//` comments, so `// REVIEW [CRITICAL]: ...` is valid and won't break compilation
- Use unique reference IDs so annotations can be cross-referenced from the report
- The `--clean` flag uses `sed` or similar to strip lines matching `// REVIEW` and `// suggested:`
- This approach is lightweight — no LSP plugin or IDE extension needed

### Implementation
- Update: `scripts/review.sh` (add annotation injection logic)
- Update: `scripts/review.sh` (add `--clean` flag for stripping annotations)

---

## Story 16.5: Quality Gate Integration

**ID:** 16.5
**Title:** Quality Gate Integration
**Points:** 4
**Priority:** P1

### User Story
- **As a** engineering lead ensuring code quality
- **I want** critical review findings to block story completion
- **So that** no story is marked complete with unresolved critical issues

### Acceptance Criteria
- [x] Quality gate script at `scripts/review-gate.sh` that:
  - Runs review via 16.2 if no report exists yet
  - Parses the latest review report for CRITICAL findings
  - Exits with code 0 if no criticals found
  - Exits with code 1 if criticals exist, listing them with file paths
  - Accepts `--override` flag that allows bypassing the gate with a reason logged to the report
- [x] Gate output is clear and actionable: "❌ Gate FAILED — 3 critical issues found. Run 'make review' to re-review after fixes."
- [x] Override logs reason to report: "⚠️ OVERRIDE by <user> on <date>: <reason>"
- [x] Override reason is required (non-empty) — cannot bypass without documenting why
- [x] Gate integration step documented in the BMAD story workflow so agents know to run it before marking stories complete
- [x] `.bmad-review-override` file tracks historical overrides (append-only log)
- [x] Clear success output: "✅ Gate PASSED — 0 critical issues."

### Technical Notes
- The gate script parses the markdown report — look for `**Critical Findings**` section and count unresolved criticals
- Since it's markdown, grep/sed/awk can handle the parsing, or a simple Kotlin script if needed
- The gate runs after the review step (16.2) in the workflow
- Hooks into the existing BMAD story-implementation.md as a post-implementation validation step

### Implementation
- Create: `scripts/review-gate.sh`
- Update: `_bmad/bmm/tasks/story-implementation.md` (add quality gate step)

---

## Story 16.6: Linear "In Review" State Integration

**ID:** 16.6
**Title:** Linear "In Review" State Integration
**Points:** 5
**Priority:** P1

### User Story
- **As a** engineering lead managing the development workflow
- **I want** the orchestrator to automatically detect when an issue enters a "Review" state and launch Claude Code review
- **So that** code review happens automatically as part of the issue lifecycle without manual triggers

### Acceptance Criteria
- [x] `ClaudeReviewRuntime.kt` created as a one-shot agent runtime that runs `claude --print` as a subprocess, piping the rendered prompt to stdin and capturing stdout as the review output
- [x] `"claude"` registered as a supported agent kind in `AgentRuntimeFactory` — mapped to `ClaudeReviewRuntime`
- [x] `WORKFLOW.md` updated: `"In Review"` added to `active_states` so the orchestrator polls for issues in this state
- [x] `WORKFLOW.md` "In Review" stage configured with `agent_kind: "claude"` and `command: "claude --print"` — review runs via Claude Code instead of opencode
- [x] `WORKFLOW.md` "In Review" stage has `on_complete_state: "Done"` — issue auto-transitions after review completes
- [x] `prompts/review.md` updated as a Liquid template for Claude Code review — tells Claude to examine workspace code via `git diff`, produce structured report with severity categories, and include Koncerto-specific review standards
- [x] Existing orchestrator flow handles the full lifecycle: Todo → agent implements → transition to "In Review" → next poll detects state → dispatches `ClaudeReviewRuntime` → review completes → transition to "Done"
- [x] `_bmad-output/review-reports/` directory exists for report output

### Technical Notes
- `ClaudeReviewRuntime` extends `AgentRuntime` directly (not `StdioAgentRuntime`) because Claude Code does not use JSON-RPC — it uses a simple stdin/stdout one-shot protocol
- The runtime captures the rendered prompt from `turn/start` JSON-RPC call, pipes it to `claude --print`, and emits `SessionStarted` + `TurnCompleted` events for compatibility with the existing `DefaultAgentRunner` flow
- No changes needed to `DefaultAgentRunner.runWithRetry()` — the standard flow works because the runtime handles events correctly
- Existing `reconcile()` and `fetchAndDispatch()` logic handles the state transition detection naturally — no special listener code needed
- The workspace from the implementation phase is reused (already contains the code)
- Git operations in `runWithRetry()` (commit, push, PR creation) are no-ops during review since there are no code changes

### Implementation
- Create: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/ClaudeReviewRuntime.kt`
- Update: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRuntime.kt` (register "claude")
- Update: `WORKFLOW.md` (active_states + In Review stage config)
- Update: `prompts/review.md` (Claude Code review prompt)
- Create: `_bmad-output/review-reports/` (output directory)

---

## Story 16.7: Review-Comment-Fix Loop with Status Management

**ID:** 16.7
**Title:** Review-Comment-Fix Loop with Status Management
**Points:** 8
**Priority:** P1

### User Story
- **As an** engineering lead managing code quality
- **I want** the orchestrator to automatically move issues to a "Needs Fix" state when Claude Code finds critical issues, dispatch the coder agent to fix them, then re-review
- **So that** review findings are automatically addressed without manual intervention, with a retry limit to prevent infinite loops

### Acceptance Criteria
- [x] `on_failure_state: "Needs Fix"` field added to `StageAgentConfig` — allows review stages to specify a fallback state when review fails
- [x] `max_review_attempts` field added to `StageAgentConfig` — caps the number of review-fix cycles (default 3)
- [x] `ServiceConfig.parseStages()` parses both `on_failure_state` and `max_review_attempts` from YAML config
- [x] `ClaudeReviewRuntime` parses Claude's output for pass/fail indicators (`❌ FAIL` or `**Critical:** N` with N > 0)
- [x] `ClaudeReviewRuntime` writes `.review-status` file in workspace (content: `pass` or `fail`) and `.review-attempt` counter
- [x] `transitionOnComplete()` in `DispatchService` reads `.review-status` before transitioning:
  - `pass` → use `on_complete_state` (e.g., "Done")
  - `fail` → use `on_failure_state` (e.g., "Needs Fix") if within max attempts
  - max attempts exceeded → force `on_complete_state` regardless
- [x] `WORKFLOW.md` updated: `"Needs Fix"` added to `active_states`, "Needs Fix" stage configured with `opencode` agent and `prompts/fix-review.md`
- [x] `WORKFLOW.md` "In Review" stage configured with `on_failure_state: "Needs Fix"` and `max_review_attempts: 3`
- [x] `prompts/fix-review.md` created — instructs opencode to read review report from `_bmad-output/review-reports/`, fix critical findings in order, verify with `./gradlew build` + `./gradlew test`, commit
- [x] Full lifecycle works: Todo → In Review → [fail] → Needs Fix → [opencode fixes] → In Review → [re-review] → Done (or force Done after 3 attempts)
- [x] README.md updated with complete Linear State Workflow documentation
- [x] README.md documents the "claude" agent kind in the Agent Runtimes table
- [x] Build compiles successfully

### Technical Notes
- The `.review-status` file is written by `ClaudeReviewRuntime.runReview()` after parsing Claude's stdout — no change to the review prompt needed for the status detection
- The `.review-attempt` file is incremented each review cycle; deleted when review passes or max attempts reached
- `resolveReviewTargetState()` follows the same file-reading pattern as `readClarification()` (proof of pattern)
- `on_failure_state` is generic enough to be used by any stage, not just review — any agent kind could use it for conditional next-state logic
- The review output is also emitted to `runtime.output` SharedFlow, making it available to `onAgentOutput` handlers for dashboard streaming

### Implementation
- Update: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt` (StageAgentConfig fields + parseStages)
- Update: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/ClaudeReviewRuntime.kt` (write .review-status, parse output)
- Update: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt` (resolveReviewTargetState)
- Update: `WORKFLOW.md` (Needs Fix stage, on_failure_state, max_review_attempts)
- Update: `prompts/review.md` (structured markdown output with ✅/❌)
- Create: `prompts/fix-review.md` (fix review findings prompt)
- Update: `README.md` (workflow documentation + claude agent kind)
