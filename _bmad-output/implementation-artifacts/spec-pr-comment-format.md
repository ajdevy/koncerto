---
title: 'Concise house style for auto-generated PR/Linear comments'
type: 'refactor'
created: '2026-07-09'
status: 'done'
context: []
baseline_commit: '354882d4875231d127b8ba5c2436315a13cd7139'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Koncerto's auto-generated PR review comments and demo-recording comments are unreadable walls of bold text: the review prompt (`prompts/review.md`) emits a redundant top *and* bottom verdict, an always-on "Passed Checks" list, and multi-line bold-label findings; the three demo builders stack `**Bold:**` key-value labels. There is no shared house style.

**Approach:** Define one concise, scannable house style — emoji-verdict heading, a one-line TL;DR, a `·`-separated metadata strip, findings in a severity table, and long/non-blocking detail collapsed in `<details>` — and apply it to the review prompt and all three demo comment builders. Restyle (not remove) the orchestrator's runtime header, which alone knows the sequence number and model.

## Boundaries & Constraints

**Always:**
- The reviewer output's first non-blank line MUST start with a bare `✅` or `❌` (no `>`, no `#` prefix) and MUST contain no leading `---` line, or `postDetailedReviewAsPrComment`'s parser truncates the body.
- Preserve the substring `Claude Review #<n>` in the orchestrator header (pinned by `AutoReviewOrchestratorTest`).
- Shared vocab: `✅` pass/done · `❌` fail · `⚠️` notes · `⏭️` skipped. `###` heading = leading emoji + em-dash + status. Monospace ticket id. Single italic footer.
- Keep per-module line coverage ≥ 80% (jacoco gate); update touched test assertions in lockstep.

**Ask First:** none anticipated.

**Never:**
- Do not change the `postDetailedReviewAsPrComment` parsing logic or the `gh` invocation.
- Do not touch the demo-secrets feature or any file outside the Code Map.
- Do not remove the runtime header (it carries sequence + model the prompt cannot know).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Review FAIL | reviewer text starting `❌` | header prepended; body preserved intact; first line `❌`; contains `Claude Review #n` | parser keeps full content |
| Review PASS | reviewer text starting `✅`, no blockers | verdict + TL;DR; passed checks collapsed in `<details>` | — |
| Demo success | task with duration, size, url | `### 🎥 Demo Recorded — ✅ <platform>`; strip `duration · size · [▶ Watch](url)`; body has url + ticket id | — |
| Demo null metrics | durationMs=null, fileSizeBytes=null | strip renders `N/A` via helpers, no crash | — |
| Demo skipped | issueId, identifier, reason | `### 🎥 Demo Skipped — ⏭️ \`<id>\``; body has reason | — |
| Demo failed (Linear) | task + error | `### 🎥 Demo Failed — ❌ <platform>`; TL;DR = error; body has error | — |
| PR demo failure | prNumber, error, logs > 5000 chars | `### 🎥 Demo Failed — ❌`; TL;DR = error; logs truncated to 5000 in `<details>`; italic footer | gh missing/non-zero → logged, no throw |

</frozen-after-approval>

## Code Map

- `prompts/review.md` -- LLM reviewer output spec; the source text of the review PR comment body (not covered by any Kotlin test)
- `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt` -- `postDetailedReviewAsPrComment` (~451-479): strips reviewer output, prepends header, appends demo link
- `koncerto-demo/src/main/kotlin/com/flexsentlabs/koncerto/demo/report/LinearReportPublisher.kt` -- `buildCommentBody` / `buildSkippedBody` / `buildFailureBody` + `formatDuration` / `formatFileSize`
- `koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/DemoFailureReporter.kt` -- `postFailure`: PR demo-failure comment
- `koncerto-demo/src/test/kotlin/.../LinearReportPublisherTest.kt` -- asserts heading substrings (break on rename)
- `koncerto-deploy/src/test/kotlin/.../DemoFailureReporterTest.kt` -- runs-without-throw only

## Tasks & Acceptance

**Execution:**
- [x] `prompts/review.md` -- rewrite the **Output Format** section: first line bare `✅ **Approved**` (used for both a clean pass and a pass-with-warnings — the "with notes" distinction lives in the TL;DR text, never in the leading emoji) or `❌ **Changes requested**`, each followed by an em-dash one-line TL;DR (the em-dash lives only in the template, do not add a second one). Do NOT introduce a third leading verdict emoji (e.g. `⚠️`) — the Always constraint requires a bare `✅`/`❌` first character and the orchestrator parser only special-cases those two. Next line: `**N blocking · N warnings · N suggestions** · N files`. Blockers in a table with a labeled severity column (`| Sev | Location | Issue | Fix |`) using `🔴` and `` `file.kt:line` ``, plus a one-clause `Fix` column so blockers stay actionable. State the FAIL-severity rule exactly once (do not restate the Mandate section's wording). Instruct the model to escape any `|` inside table cells as `\|` and keep each cell to a single line. Warnings, suggestions, and passed checks go inside one collapsed `<details>` (🟡 warnings, 🔵 suggestions, ✅ passed). Delete the closing "Review PASSED/FAILED" line and the top Summary/Result block. Add an explicit rule: begin directly with the `✅`/`❌` line, no leading `---`.
- [x] `AutoReviewOrchestrator.kt` -- change the header string (~L477) to `### 🤖 Claude Review #$sequence · $modelName` (keeps `Claude Review #n` substring). No parser/gh changes.
- [x] `LinearReportPublisher.kt` -- rewrite the three builders to: emoji-verdict `###` heading with platform/id, one `> ` TL;DR line, one `·`-separated metadata strip (using existing `formatDuration`/`formatFileSize`), a `_… koncerto_` italic footer. Keep the recording URL, ticket id, and error text present in the body. Quote every line of freeform `reason`/`errorMessage` text with its own `> ` prefix (not just the first line) so an embedded newline can't break the blockquote.
- [x] `DemoFailureReporter.kt` -- change heading to `### 🎥 Demo Failed — ❌`, add a `> $error` TL;DR line (quoting every line of `error`, same newline-safety as above), keep the `<details>` logs (5000-char truncation) and the `_Auto-generated by koncerto_` footer.
- [x] `LinearReportPublisherTest.kt` -- update the three heading assertions to the new copy (`Demo Recorded`, `Demo Failed`, `Demo Skipped`); keep url/id/error/reason asserts. Add cases for null duration + null file size (`N/A`), a `< 1 KB` size, and a `≥ 1 MB` size so the helper branches stay covered. The null-metrics case must assert both fields independently (not a single shared `"N/A"` substring check) so a regression in either field is caught.

**Acceptance Criteria:**
- Given the reviewer emits the new format, when `postDetailedReviewAsPrComment` parses it, then the full body survives (first line `✅`/`❌`) and contains `Claude Review #n`.
- Given a completed demo task, when `report()` runs, then the body contains the new heading, the recording URL, and the ticket id.
- Given a task with null duration and null size, when `report()` runs, then the body renders `N/A` and does not throw.
- Given all touched modules, when `./gradlew test -Pjacoco` runs, then tests pass and `jacocoCoverageVerification` stays ≥ 80% per module.

## Spec Change Log

- **Triggered by:** bad_spec finding from step-04 review round 1 (independently surfaced by both the edge-case hunter and the acceptance auditor). The original Task 1 wording specified a third leading verdict emoji, `⚠️ **Approved with notes**`, as the first line of reviewer output. This directly conflicts with the frozen `Always` constraint ("first non-blank line MUST start with a bare ✅ or ❌"), and `AutoReviewOrchestrator.kt`'s parser only special-cases `✅`/`❌` — a `⚠️`-led line falls through to the generic no-marker fallback, an undesigned and untested path.
  - **Amended:** Task 1 now requires `✅ **Approved**` for both a clean pass and a pass-with-warnings — the "with notes" distinction lives only in the TL;DR text, never in the leading emoji. Added a Design Notes example showing this.
  - **Known-bad state avoided:** a reviewer-emitted `⚠️` first line silently exercising an unverified parser fallback branch, with no test coverage for that path.
  - **KEEP:** everything else about the format from round 1 worked and must survive re-derivation — the TL;DR line, the `**N blocking · N warnings · N suggestions** · N files` count line, the blocking table, the single collapsed `<details>` for warnings/suggestions/passed-checks, and the rule against a leading `---` or a closing verdict repeat.
  - Also folded in, same pass (patch-level, same file/task, no separate loopback needed): labeled severity column + `Fix` column on the blocking table (blind hunter #3, #8), single non-duplicated statement of the FAIL-severity rule (blind hunter #6), a clarified em-dash instruction (blind hunter #7), and an explicit pipe/single-line escaping rule for table cells (edge hunter #5).
  - Also folded into `LinearReportPublisher.kt` / `DemoFailureReporter.kt` tasks: quote every line of freeform `reason`/`errorMessage`/`error` text (not just the first line) so an embedded newline can't break the `> ` blockquote (edge hunter #2, #3). Folded into the `LinearReportPublisherTest.kt` task: the null-metrics case must assert both fields independently, not one shared `"N/A"` substring check (blind hunter #10).

- **Round 2 patches** (no bad_spec/intent_gap this round — all findings were patch or reject; applied directly, no loopback): added the missing `_Recorded by koncerto_` italic footer to all three `LinearReportPublisher.kt` builders (the spec already required it via the frozen Always vocab rule and Task 3 — this was a pure implementation gap, not a spec defect); added the combined-both-null test case the AC literally named (round 1's two single-field tests didn't cover it); fixed stale `❌ FAIL` wording left in `review.md`'s pipeline-artifact instruction; fixed a self-contradictory verdict-line instruction in `review.md`; de-duplicated the warning/suggestion counts between the top count line and the `<details>` summary; fixed `blockquote()`/`DemoFailureReporter`'s inline quoting to trim a trailing-newline artifact and fall back to placeholder text on blank input; moved `buildSkippedBody`'s ticket id from the header to the footer strip to match the other two builders' shape.

## Design Notes

Golden review-output header (what the reviewer writes to `.review-output-detailed`; orchestrator prepends its own `###` line above it):

```
❌ **Changes requested** — one null-safety bug in the retry path blocks merge.

**2 blocking · 1 warning · 3 suggestions** · 5 files

| Sev | Location | Issue | Fix |
|--|----------|-------|-----|
| 🔴 | `DispatchService.kt:214` | `issue.id` may be null here → NPE on retry | null-check before dereferencing |
| 🔴 | `AutoReviewOrchestrator.kt:504` | branch name unescaped in `gh` args | pass via `--body-file` args array, not string interpolation |

<details><summary>⚠️ 1 warning · 💡 3 suggestions · ✅ passed checks</summary>

- 🟡 `RuntimeState.kt:88` — swallowed exception hides dispatch failures
- 🔵 `ProjectConfig.kt:40` — prefer `Result<T,E>` over throwing
- ✅ Passed: security, performance, conventions
</details>
```

A pass-with-warnings case still leads with `✅` — only the TL;DR text signals "with notes":

```
✅ **Approved** — no blockers; two warnings worth a look before the next PR.
```

Golden demo-success body:

```
### 🎥 Demo Recorded — ✅ PLAYWRIGHT

> 1m 5s · 2 KB · [▶ Watch recording](https://…/recording.webm)

`KONC-123` · recorded 2026-01-01T00:01:05Z
```

## Verification

**Commands:**
- `./gradlew :koncerto-demo:test :koncerto-deploy:test :koncerto-orchestrator:test -Pjacoco` -- expected: BUILD SUCCESSFUL, `jacocoCoverageVerification` passes
- Manual: render the two golden blocks in a Markdown preview -- expected: table + collapsed section display correctly

## Suggested Review Order

**Review comment format** (the house style everything else mirrors)

- Start here: the two-token verdict contract, count line, blocking table, collapsed details — the whole design intent in one section.
  [`review.md:47`](../../prompts/review.md#L47)
- The runtime wrapper that prepends the `#sequence · model` header the prompt can't know; parser above it is deliberately untouched.
  [`AutoReviewOrchestrator.kt:419`](../../koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt#L419)

**Demo comment format** (status reports in the same DNA)

- The three Linear builders: emoji-verdict heading, `>` TL;DR, `·` metadata strip, shared footer.
  [`LinearReportPublisher.kt:42`](../../koncerto-demo/src/main/kotlin/com/flexsentlabs/koncerto/demo/report/LinearReportPublisher.kt#L42)
- Newline-safe, blank-safe blockquote helper — the boundary fix that prevents broken quotes from raw error text.
  [`LinearReportPublisher.kt:78`](../../koncerto-demo/src/main/kotlin/com/flexsentlabs/koncerto/demo/report/LinearReportPublisher.kt#L78)
- The PR-failure comment: same heading/TL;DR/blank-guard, logs still collapsed.
  [`DemoFailureReporter.kt:11`](../../koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/DemoFailureReporter.kt#L11)

**Tests** (supporting)

- Heading copy, metadata branches (both-null, sub-KB, multi-MB), footer, multi-line + trailing-newline + blank-error quoting.
  [`LinearReportPublisherTest.kt:52`](../../koncerto-demo/src/test/kotlin/com/flexsentlabs/koncerto/demo/LinearReportPublisherTest.kt#L52)
- Blank error and trailing-newline paths for the PR-failure reporter.
  [`DemoFailureReporterTest.kt:28`](../../koncerto-deploy/src/test/kotlin/com/flexsentlabs/koncerto/deploy/DemoFailureReporterTest.kt#L28)
