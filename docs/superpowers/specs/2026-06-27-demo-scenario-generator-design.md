# Demo Scenario Generator — Design Spec

**Date:** 2026-06-27
**Status:** Approved

## Problem

Demo videos (e.g. FLE-51) are recorded with scenarios generated as a side-effect of the code review — the review agent appends a `demo_scenario:` YAML block at the end of its review output. Because the review agent's primary job is code review, it produces generic, low-quality scenarios: typically just `navigate` + `wait` steps with no scrolling, clicking, or form interactions. The result is a video where a website loads and nothing happens.

## Goal

Replace the embedded scenario generation with a dedicated `DemoScenarioGenerator` service that makes a focused, standalone opencode free-model call to produce rich, interactive Playwright scenarios.

---

## Architecture

### New component: `DemoScenarioGenerator`

**Location:** `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGenerator.kt`

A single focused class with one public method:

```kotlin
suspend fun generate(issue: Issue, workspace: Workspace): String?
```

Returns the path to the saved scenario file on success, or `null` on any failure (non-fatal).

**Dependencies:** `StructuredLogger`, opencode command string (passed from `ServiceConfig`). No heavy infrastructure dependencies.

---

## Prompt Construction

`generate()` assembles a prompt from four sources, all available before deployment:

1. **System instructions** — content of `prompts/demo-scenario.md` from the workspace (existing schema doc)
2. **Issue context** — `issue.title` + `issue.description`
3. **Project README** — `README.md` from the workspace root, capped at 3000 chars
4. **PR diff** — output of `git diff main...HEAD` in the workspace directory, capped at 8000 chars

Assembled prompt structure:

```
{demo-scenario.md content}

## Issue
{issue.title}: {issue.description}

## Project README
{readme content}

## PR Changes
{git diff output}

Generate the demo_scenario YAML now.
```

---

## Subprocess Invocation

```bash
opencode run --model <model> "<prompt>"
```

- Working directory: `workspace.path`
- Stdout captured; stderr logged at debug level
- Timeout: 60 seconds
- Model fallback (linear, no shared state): `opencode-free-1` → `opencode-free-2` → `opencode-free-3`
- On all models failing, or no `demo_scenario:` block found in output → log `warn`, return `null`

`extractScenarioBlock()` logic (moved from `AutoReviewOrchestrator`) parses the `demo_scenario:` YAML block from stdout.

---

## Output

On success, the scenario is written to:
- `/tmp/koncerto-demo/{issue.id}-scenario.yaml`
- `/tmp/koncerto-demo/{issue.identifier}-scenario.yaml`

These are the same paths `DemoRecordingService.resolveScenarioPath()` already reads — no downstream changes required.

---

## Integration: `AutoReviewOrchestrator`

**Constructor change:** one new nullable parameter:

```kotlin
private val demoScenarioGenerator: DemoScenarioGenerator? = null
```

All existing call sites omit it (backward compatible, defaults to `null`).

**`onCodingComplete` change:** replace `saveDemoScenario(issue, workspace)` with:

```kotlin
demoScenarioGenerator?.generate(issue, workspace)
```

**Deleted from `AutoReviewOrchestrator`:**
- `saveDemoScenario()` private method
- `extractScenarioBlock()` private method (moved to `DemoScenarioGenerator`)

**`prompts/review.md` change:** remove lines 70–90 (the "Demo Scenario" section). The review agent should focus on review only.

---

## Error Handling

All failures are non-fatal — recording always proceeds without a scenario (same behavior as today when no scenario file exists):

| Failure case | Logged as | Return |
|---|---|---|
| opencode not on PATH | `warn: demo_scenario_generator_unavailable` | `null` |
| Model exits non-zero | `warn: demo_scenario_model_failed` | retry next model |
| All models exhausted | `warn: demo_scenario_all_models_failed` | `null` |
| No `demo_scenario:` block in output | `warn: demo_scenario_extract_failed` | `null` |
| File write fails | `warn: demo_scenario_save_failed` | `null` |
| Timeout (60s) | `warn: demo_scenario_timeout` | `null` |

---

## Testing

**`DemoScenarioGeneratorTest`** (unit, `koncerto-orchestrator`):
- Happy path: mocked subprocess returns valid YAML → file written, path returned
- All models fail → `null` returned, no exception thrown
- Malformed output (no `demo_scenario:` block) → `null`
- Timeout → `null`
- Uses a test double / fake `ProcessBuilder` factory injected via constructor

**`AutoReviewOrchestratorTest`** (existing):
- Add: `demoScenarioGenerator.generate()` called when review passes
- Add: `demoScenarioGenerator.generate()` NOT called when review fails
- Existing tests stay green (pass `null` for `demoScenarioGenerator`)

---

## Files Changed

| File | Change |
|---|---|
| `koncerto-orchestrator/.../DemoScenarioGenerator.kt` | **New** |
| `koncerto-orchestrator/.../AutoReviewOrchestrator.kt` | Remove `saveDemoScenario()`, `extractScenarioBlock()`; add `demoScenarioGenerator` param; call `generate()` on review pass |
| `prompts/review.md` | Remove lines 70–90 (demo scenario generation instruction) |
| `koncerto-orchestrator/.../DemoScenarioGeneratorTest.kt` | **New** |
| `koncerto-orchestrator/.../AutoReviewOrchestratorTest.kt` | Add two assertions |

No changes to `koncerto-demo`, `PlaywrightRecorder`, `DemoRecordingService`, or the Playwright executor script.
