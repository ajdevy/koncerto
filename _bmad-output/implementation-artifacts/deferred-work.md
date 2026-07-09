# Deferred Work

Findings surfaced incidentally during reviews that are NOT part of the story that surfaced them.

## From: spec-demo-secrets-and-auto-recovery (review pass 1, 2026-07-05)

- **`DemoScenarioGenerator.ensureNavigatesToRealRoute` indent detection** (prior-story code, `deterministic navigation`). The `stepIndent` regex `\n(\s*)- action:` picks the first `- action:` anywhere in the block; if any `- action:`-shaped text precedes `steps:` (e.g. an embedded example), the injected navigate step could be mis-indented, producing malformed YAML. Low real-world risk today because the block passed in is the isolated, model-extracted `demo_scenario` YAML. Fix: anchor the indent capture to occur after the `steps:` key, and validate the result parses before returning. Not caused by the secrets/preflight/recovery story.

## Test coverage: header/heading content assertions (from spec-pr-comment-format review round 1)

`AutoReviewOrchestratorTest.kt` asserts only the `Claude Review #n` substring on the orchestrator's PR-comment header, and `DemoFailureReporterTest.kt` asserts only that `postFailure` doesn't throw — neither pins the full new heading text (`### 🤖 Claude Review #$sequence · $modelName`, `### 🎥 Demo Failed — ❌`). Pre-existing gap, not caused by the comment-format change, but worth a follow-up story to add explicit content assertions so future header edits get regression protection.

`DemoFailureReporter.postFailure` builds its `gh pr comment` body inline and shells out directly — there is no seam to capture the constructed body in a test (unlike `LinearReportPublisher`, which goes through an injectable `TrackerClient`). Its multi-line-safe blockquote fix (this change) is therefore only indirectly covered by line execution, not by an assertion on the quoted output. Worth extracting body-construction into a small pure function if this class gets more format changes.
