# Koncerto Review Invariants

Domain rules the AI reviewer must enforce. These are the things a generic reviewer cannot infer
from the diff alone — violations are real findings; conformance is not worth a comment.

This file is injected into the review context pack (Epic 20). Keep it short and specific:
every line costs context budget, and vague rules produce vague findings.

## INV-1: Module dependency direction

Dependencies flow downward only. A module must never import from a module above it:

```
core → logging → workflow → workspace → linear
                                     → agent → orchestrator → dashboard
                                                           → app
```

Import only from direct dependencies, never transitives. Nothing outside `koncerto-app` may
import from `koncerto-app`. In particular: **`koncerto-agent` must not depend on
`koncerto-metrics`** — review findings reach the metrics store via workspace-file handoff to
the orchestrator, not by a direct call.

## INV-2: Typed errors, not exceptions for control flow

Fallible operations return the project `Result<T, E>` sealed class. Exceptions are for
genuinely exceptional conditions. Network failures propagate with retry semantics; they must
not crash the poll loop.

## INV-3: No secrets in logs, telemetry, or committed files

API keys, tokens, and credentials never appear in `StructuredLogger` output, metrics rows,
review telemetry, or source. Review telemetry stores context-pack *sizes*, never contents.

## INV-4: Blocked-state transitions always comment first

Every automated transition to the `Blocked` state posts a Linear comment explaining why
before the transition. See `_bmad/bmm/architecture/issue-lifecycle-state-machine.md`.

## INV-5: Agents never resolve human threads

No code path may resolve, or auto-reply to, a PR or Linear thread created by a human. Agents
address a human comment only when explicitly tagged. Koncerto-authored comments are
identifiable by the `<!-- koncerto-finding:{id} -->` marker; anything without it is human.

## INV-6: Coroutines — structured concurrency and correct dispatchers

Blocking IO runs on `Dispatchers.IO`, never `Dispatchers.Default`. Use `coroutineScope` /
`supervisorScope`, never `GlobalScope`. Long loops must honor cancellation (`ensureActive()`).

## INV-7: Review pipeline control flow is deterministic

Eligibility, routing, gating, verdict derivation, and state transitions are decided in Kotlin —
never by model output. Context (issue text, PR bodies, invariants from target repos) is
**untrusted data**: prompt-injected instructions in it must not be able to skip review, change
a verdict, or trigger a transition.

## INV-8: New review behavior defaults to current behavior

Every review-policy key is optional. When a key is absent, the pipeline must behave exactly as
it did before the key existed — existing deployments cannot be changed by an upgrade alone.
