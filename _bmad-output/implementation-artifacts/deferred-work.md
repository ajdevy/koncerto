# Deferred Work

Findings surfaced incidentally during reviews that are NOT part of the story that surfaced them.

## From: spec-demo-secrets-and-auto-recovery (review pass 1, 2026-07-05)

- **`DemoScenarioGenerator.ensureNavigatesToRealRoute` indent detection** (prior-story code, `deterministic navigation`). The `stepIndent` regex `\n(\s*)- action:` picks the first `- action:` anywhere in the block; if any `- action:`-shaped text precedes `steps:` (e.g. an embedded example), the injected navigate step could be mis-indented, producing malformed YAML. Low real-world risk today because the block passed in is the isolated, model-extracted `demo_scenario` YAML. Fix: anchor the indent capture to occur after the `steps:` key, and validate the result parses before returning. Not caused by the secrets/preflight/recovery story.
