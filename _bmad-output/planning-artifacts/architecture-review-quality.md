# Architecture Document: Review Quality (Epics 18–23)

**BMAD Phase:** Solutioning (Architect)
**Date:** 2026-07-16
**Inputs:** `prd-review-quality.md`, `analysis-ai-review-usefulness.md`, `architecture.md` (§14 Auto-Review, §17 Metrics)
**Status:** Draft for review

---

## 1. System Overview

The Review Quality system upgrades the existing auto-review pipeline (architecture.md §14) from a single opaque `claude --print` call into a staged pipeline with deterministic Kotlin orchestration around one-or-more model calls:

```
Issue enters "In Review"
  │
  ├─ 1. EligibilityChecker      (Kotlin heuristics — skip artifact/generated/docs-only diffs)
  ├─ 2. RiskRouter              (Kotlin heuristics — low | standard | critical tier)
  ├─ 3. ReviewContextBuilder    (context pack: intent, invariants, neighborhood — budgeted)
  ├─ 4. ClaudeReviewRuntime v2  (JSON envelope: result text + structured findings + usage)
  │      └─ critical tier: parallel specialist runs (Epic 23), merged + deduped
  ├─ 5. PublicationGate         (Kotlin — threshold filter; dropped findings persisted, not posted)
  ├─ 6. Publish                 (PR comment with per-finding markers; report file)
  ├─ 7. Persist                 (review_runs + review_findings in koncerto-metrics)
  └─ 8. Transition              (mode-aware: advisory always completes; blocking keeps Needs Fix loop)
         └─ Needs Fix loop reports per-finding dispositions → outcome tracking
```

Design principle carried over from the PRD: **everything that can be deterministic is Kotlin, not prompt.** Eligibility, routing, gating, dedup, and outcome bookkeeping are code; the model only reads code and emits findings.

## 2. Technology Stack

No new technologies. Kotlin/coroutines, existing SQLite (JDBC) + Micrometer/Prometheus in `koncerto-metrics`, Liquid templates via `koncerto-workflow`, `claude` CLI subprocess via `ClaudeReviewRuntime`, `gh` CLI for PR comments. One CLI flag change: review commands gain `--output-format json` (see D-1).

## 3. Module Architecture

### 3.1 Placement (respects existing dependency graph — no new edges)

| Module | New/changed components | Responsibility |
|--------|------------------------|----------------|
| koncerto-core | `ReviewFinding`, `ReviewVerdict`, `RiskTier`, `ReviewRunRecord` (data classes); `StageAgentConfig` fields: `reviewMode`, `riskRules`, `publicationThresholds`, `contextBudgetChars` | Shared types + config parsing (ServiceConfig.parseStages) |
| koncerto-agent | `ClaudeReviewRuntime` v2; `ReviewOutputParser` | Run CLI with JSON output format; parse envelope → findings + usage; write handoff files; emit real `usage` in `TurnCompleted` |
| koncerto-orchestrator | `ReviewEligibilityChecker`, `RiskRouter`, `ReviewContextBuilder`, `PublicationGate`, `FindingOutcomeTracker`; changes in `AutoReviewOrchestrator`, `DispatchService` | Pipeline stages 1–3, 5–8 |
| koncerto-metrics | `ReviewMetricsRepository` (interface) + SQLite impl + Prometheus counters | Persist runs/findings; expose aggregates |
| koncerto-dashboard | `/api/v1/review/*` endpoints | Findings list, feedback buttons, baseline/calibration reports |
| prompts/ | `review.md` v2, `fix-review.md` v2, `review-security.md`, `review-reliability.md`, `review-architecture.md` (Epic 23) | Model-facing contracts |

Module rules hold: agent does not depend on metrics — the runtime hands findings to the orchestrator via workspace files (existing `.review-status` pattern), and the orchestrator persists them (it already holds `metricsRepository`).

### 3.2 File handoff contract (runtime → orchestrator)

| File | Writer | Content |
|------|--------|---------|
| `.review-status` | runtime | `pass` / `fail` (unchanged) |
| `.review-output` | runtime | human-readable verdict text (unchanged) |
| `.review-findings.json` | runtime | **new** — parsed findings array + usage + prompt version + parse status |
| `.review-attempt` | runtime | attempt counter (unchanged) |
| `.review-fix-report.json` | fix agent | **new** — per-finding disposition (see §4.4) |

## 4. Data Flow

### 4.1 Structured review output (Epic 18)

`prompts/review.md` v2 keeps the human verdict format (first line ✅/❌ etc. — PR comment rendering unchanged) and **appends** one fenced block the parser extracts:

````
```review-findings
{"findings":[{"seq":1,"category":"correctness","severity":"critical","confidence":0.9,
  "file":"src/X.kt","line":42,"description":"...","expectedAction":"...",
  "evidence":"violates invariant INV-3 from review-invariants.md"}]}
```
````

`ReviewOutputParser` (koncerto-agent):
1. Parse CLI JSON envelope (`--output-format json`): `result`, `usage.input_tokens/output_tokens`, `duration_ms`, `is_error`.
2. Extract fenced `review-findings` block from `result` via regex; `kotlinx.serialization` decode.
3. On any parse failure → fall back to today's verdict-string parsing, set `parse_status: fallback` in `.review-findings.json` (NFR-02: no pipeline stall; failures visible in telemetry).

Verdict derivation moves from string-matching `❌ FAIL` to `findings.any { severity == critical }`, with the string match kept as fallback.

### 4.2 Eligibility & routing (Epic 19)

Both run in `AutoReviewOrchestrator.onCodingComplete()` **before** dispatching the review agent, using `git diff --name-only` / `--numstat` through the existing `GhProcessRunner`-style seam (testable).

- `ReviewEligibilityChecker`: diff ∩ `skip_globs` == diff → skip. Defaults: `.koncerto/**`, `.review-*`, `*.lock`, `**/generated/**`, docs-only (`**/*.md` unless `review-invariants.md` critical glob matches). Skip is recorded as a `review_runs` row with `eligibility: skipped_<reason>` and zero tokens; issue transitions straight to `on_complete_state`. The in-prompt artifact special-case is then deleted from `prompts/review.md`.
- `RiskRouter`: `critical_globs` match (auth/payment/secrets paths, `WORKFLOW.md`-configured per project) → **critical**; diff > `large_change_loc` (default 500) or touches > `many_files` (default 15) → at least **standard**; otherwise **low**. Tier selects `{model, prompt, maxContextChars}` from stage config. v1 is heuristics-only — no LLM routing call (NFR-04).

### 4.3 Context pack (Epic 20)

`ReviewContextBuilder` (orchestrator — has `TrackerClient` + workspace) assembles Liquid variables consumed by `prompts/review.md` v2, filled in priority order until `contextBudgetChars` (default 60k chars ≈ 15k tokens) is exhausted:

1. Issue description + acceptance criteria (from Linear issue)
2. PR body (intent/risks — via `gh pr view`)
3. `review-invariants.md` from target repo root (path configurable)
4. Test files matching changed files
5. Neighboring source files (same package/dir as changed files, smallest first)

Pack composition (which sections included, chars each, truncations) is logged into the run record — context is an experiment variable, so it must be observable.

### 4.4 Outcome tracking (Epic 18/22) — resolves PRD OQ-2

Hybrid, in precedence order:

1. **Fix-agent self-report:** `prompts/fix-review.md` v2 requires writing `.review-fix-report.json`: `[{findingId, disposition: fixed|wont_fix|not_a_bug, note}]`. `FindingOutcomeTracker` ingests it on the next review cycle (`outcome_source: fix_agent`).
2. **Re-review corroboration:** if the next review run no longer reports a matching finding (same file + category, line within ±10), mark `likely_fixed` (`outcome_source: rereview`) — catches fix-agent under-reporting.
3. **Human feedback (Epic 22):** dashboard labels override both (`outcome_source: human`).

Finding identity: `finding_id = {runId}-{seq}`. Published PR comments embed `<!-- koncerto-finding:{finding_id} -->` per finding so later feedback surfaces can attribute.

### 4.5 Publication gate (Epic 21) — resolves PRD OQ-1

**Decision: self-scoring rubric + deterministic gate now; independent validator pass only for the critical tier, and only in Epic 23.**

- The reviewer self-assigns `severity` and `confidence` per finding under rubric text in the prompt (definitions + anchored examples per level).
- `PublicationGate` (pure Kotlin) filters: publish if `confidence ≥ threshold[severity]` (defaults: critical 0.5, warning 0.7, suggestion 0.85 — suggestions must be near-certain to be worth attention). Dropped findings are persisted with `published=false, drop_reason`, never posted.
- Rationale: a second model pass doubles cost per review before we have baseline evidence it's needed; self-score calibration is exactly what Epic 18 telemetry + Epic 22 labels will measure (FP rate by confidence bucket). If self-scores prove inflated, the gate config already supports an `independent_validation: true` flag whose implementation slots into the Epic 23 fan-out machinery.
- Gate applies identically in advisory and blocking modes.

### 4.6 Mode-aware transition (Epic 21)

`DispatchService.resolveReviewTargetState()` gains one branch: `review_mode: advisory` → always return `on_complete_state`, even on `fail` (findings still published + persisted, verdict recorded). `blocking` → current behavior (Needs Fix loop, max attempts). Default for new projects: `advisory`; Koncerto's own WORKFLOW.md stays `blocking` (it has Epic-16 history as its baseline).

Human-thread invariant (FR-10): the only comment-writing paths are `postDetailedReviewAsPrComment` and Linear comments on state transitions — neither resolves threads today. The invariant is codified as a guard test + a documented rule in `issue-lifecycle-state-machine.md`: no future code path may call GitHub review-thread resolve mutations on threads not created by Koncerto (identified by the `koncerto-finding` marker).

### 4.7 Multi-agent critical tier (Epic 23)

For `critical` tier, `AutoReviewOrchestrator` runs the specialist prompts via `SpecialistReviewCoordinator` instead of the generalist review. Each emits the same findings schema. Kotlin merge: concat → dedup by `(file, line/10, category)` keeping highest confidence → single gate pass → one combined PR comment. The merged result is written back to the standard handoff files (`.review-status`, `.review-output`, `.review-findings.json`) so the rest of the pipeline is unchanged. Token budget: per-run cap from stage config, checked between specialists; specialists share the context pack. Per-specialist attribution is kept on the `specialist` column of each finding.

**Specialists run sequentially, not in parallel (revised during implementation — see D-11).** The original design assumed concurrent fan-out, but specialists share the issue's single workspace and each run writes the same handoff files, so concurrent runs would clobber one another's results. Parallel fan-out requires per-specialist workspace isolation, which is deferred; correctness beats wall-clock here.

## 5. APIs (koncerto-dashboard)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/review/runs?project=&since=` | GET | Run list with verdicts, tiers, tokens, durations |
| `/api/v1/review/runs/{runId}/findings` | GET | Findings incl. dropped (with drop reasons) |
| `/api/v1/review/findings/{findingId}/label` | POST | Human label: `accept` / `reject` / `false_positive` (Epic 22 — resolves PRD OQ-3: dashboard-first; GitHub reaction polling deferred as optional follow-up, meets-them-where-they-are but adds polling + mapping complexity for no owned data) |
| `/api/v1/review/baseline?window=` | GET | Baseline/calibration aggregates: HEO rate, FP rate by category/prompt-version/confidence bucket, tokens per useful finding |

`scripts/review-baseline.sh` (Story 18.4) is a thin curl wrapper over the baseline endpoint writing the markdown snapshot.

## 6. Data Models (koncerto-metrics SQLite)

```sql
CREATE TABLE IF NOT EXISTS review_runs (
  run_id            TEXT PRIMARY KEY,          -- UUID
  issue_id          TEXT NOT NULL,
  issue_identifier  TEXT NOT NULL,
  project_slug      TEXT,
  attempt           INTEGER NOT NULL,
  commit_sha        TEXT,
  pr_number         INTEGER,
  model             TEXT,
  prompt_version    TEXT,                      -- from prompt frontmatter
  risk_tier         TEXT,                      -- low|standard|critical
  review_mode       TEXT,                      -- advisory|blocking
  eligibility       TEXT NOT NULL,             -- reviewed|skipped_<reason>
  parse_status      TEXT,                      -- ok|fallback
  verdict           TEXT,                      -- pass|fail|skipped
  findings_total    INTEGER DEFAULT 0,
  findings_published INTEGER DEFAULT 0,
  input_tokens      INTEGER DEFAULT 0,
  output_tokens     INTEGER DEFAULT 0,
  duration_ms       INTEGER DEFAULT 0,
  context_pack_json TEXT,                      -- section→chars composition
  created_at        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS review_findings (
  finding_id      TEXT PRIMARY KEY,            -- {run_id}-{seq}
  run_id          TEXT NOT NULL REFERENCES review_runs(run_id),
  seq             INTEGER NOT NULL,
  specialist      TEXT,                        -- null | security | reliability | architecture
  category        TEXT NOT NULL,
  severity        TEXT NOT NULL,               -- critical|warning|suggestion
  confidence      REAL,
  file            TEXT, line INTEGER,
  description     TEXT,
  expected_action TEXT,
  evidence        TEXT,
  published       INTEGER NOT NULL DEFAULT 0,
  drop_reason     TEXT,
  outcome         TEXT,                        -- fixed|likely_fixed|wont_fix|not_a_bug|discussed|ignored
  outcome_source  TEXT,                        -- fix_agent|rereview|human
  human_label     TEXT,                        -- accept|reject|false_positive
  updated_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_findings_run ON review_findings(run_id);
```

`ReviewMetricsRepository` is a **separate interface** from `MetricsRepository` (interface segregation; existing consumers untouched), implemented by the same `SqliteMetricsRepository` class against the same DB file, same `ReentrantLock` + `Dispatchers.IO` pattern (NFR-01: async, sub-50ms enqueue). Schema is `CREATE TABLE IF NOT EXISTS` — no migration tooling needed.

Prometheus additions (via `PrometheusMetricsBinder`): `koncerto_review_runs_total{tier,verdict,eligibility}`, `koncerto_review_findings_total{severity,published}`, `koncerto_review_tokens_total`, `koncerto_review_duration_seconds` (summary).

Derived metrics (computed in queries, not stored): **HEO rate** = findings with outcome ∈ {fixed, likely_fixed, discussed} ÷ published findings; **FP rate** = human_label = false_positive ÷ human-labeled; **tokens per useful finding** = Σtokens ÷ HEO findings.

## 7. Security Considerations

- `review-invariants.md` and PR bodies are **untrusted input** into the review prompt (target repos may be third-party). The prompt must instruct the reviewer to treat context as data; the publication gate and deterministic transition logic mean embedded prompt-injection can at worst pollute finding text — it cannot skip review (eligibility is Kotlin), change states (verdict derivation is Kotlin), or execute anything.
- Dashboard label endpoint sits behind existing dashboard auth (Epic 13.04).
- No secrets in telemetry: finding `evidence` is model text; context_pack_json stores section sizes, not content.

## 8. Performance & Cost

| Concern | Approach |
|---|---|
| Telemetry overhead | Async SQLite writes, same pattern as `issue_metrics` (NFR-01) |
| Routing cost | Pure heuristics, zero model calls (NFR-04) |
| Context bloat | Hard char budget per tier; composition logged so budget is tunable with data |
| Multi-agent cost | Critical tier only; per-run token cap; enabled per-project flag, off by default (NFR-05) |
| Skipped diffs | Save a full review's tokens each — expected biggest single cost win |

## 9. Deployment / Rollout

Matches PRD §10 phases. Notable mechanics:

- **Zero-behavior-change guarantee for Phase 1 (Epic 18):** prompt v2 keeps the identical human-readable verdict contract; the JSON block is additive; parse failure falls back to today's exact path. Only observable delta: `--output-format json` on the CLI command in WORKFLOW.md stage config.
- Prompt versions bumped in frontmatter on every change; version lands on every run row → prompt changes are A/B comparable across time windows (NFR-03).
- Config is per-project in WORKFLOW.md; all new behavior (routing, gate thresholds, advisory mode, multi-agent) defaults to current behavior when keys are absent — existing deployments unaffected until opted in.

## 10. Technical Decisions

| # | Decision | Options considered | Choice | Rationale |
|---|----------|--------------------|--------|-----------|
| D-1 | Token accounting (PRD OQ-4) | (a) parse plain stdout, no usage; (b) `--output-format json` envelope | **(b)** | Envelope gives `usage`, `duration_ms`, `is_error` for free; also makes preamble-stripping obsolete. Fallback to (a) on parse failure. Also fixes `TurnCompleted(usage=null)` so review tokens finally accrue to existing `issue_metrics`. |
| D-2 | Findings extraction | (a) separate structured-output call; (b) fenced JSON block in same response | **(b)** | One call, one context; the human verdict and machine findings can't diverge; regex + kotlinx-serialization is enough |
| D-3 | Validation stage (PRD OQ-1) | (a) independent cheap-model validator pass; (b) self-scoring rubric + Kotlin threshold gate | **(b) now, (a) as critical-tier option in Epic 23** | Halves cost; calibration quality of self-scores is measurable with Epic 18+22 data before paying for independence |
| D-4 | Outcome authoring (PRD OQ-2) | (a) fix-agent self-report; (b) diff/re-review inference; (c) both | **(c)** | Self-report is precise but unreliable alone; re-review corroboration catches gaps; human labels override |
| D-5 | Feedback surface (PRD OQ-3) | (a) GitHub reaction polling; (b) dashboard buttons | **(b) first** | Fully owned, no polling/mapping complexity; comment markers keep (a) possible later |
| D-6 | Per-tier models (PRD OQ-5) | (a) tier→model mapping now; (b) after baseline | **(a) config support now, defaults identical** | The config key costs nothing; changing defaults waits for baseline data |
| D-7 | Routing implementation | (a) LLM classifier; (b) glob/size heuristics | **(b)** | Deterministic, free, testable; LLM routing only if heuristics measurably misroute |
| D-8 | Findings persistence path | (a) runtime writes to metrics directly; (b) workspace file handoff → orchestrator persists | **(b)** | Preserves module graph (agent ↛ metrics); proven pattern (`.review-status`) |
| D-9 | Repository interface | (a) extend `MetricsRepository`; (b) new `ReviewMetricsRepository` | **(b)** | Interface segregation; existing consumers and fakes untouched |
| D-10 | Context injection (added during implementation) | (a) Liquid `{{ review_context }}` var threaded through `AgentRunner.run`; (b) orchestrator appends a `## Review Context` section to the prompt string | **(b)** | (a) means adding a parameter to the `AgentRunner` interface, which ~8 test fakes implement. (b) needs no interface change. Context text is passed through `neutralizeTemplating()` first: the prompt is Liquid-rendered downstream, and the pack quotes files from the repo under review — `{{ }}` in a target repo must never reach the template engine. |
| D-11 | Specialist concurrency (revised during implementation) | (a) parallel `async` fan-out; (b) sequential | **(b)** | Specialists share one workspace and one set of `.review-*` handoff files; concurrent runs clobber each other. Revisit once per-specialist workspace isolation exists. |

## 11. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Model doesn't reliably emit valid JSON block | Fallback path + `parse_status` telemetry; prompt anchored with exact example; tolerated failure rate visible from day one |
| Self-assigned confidence inflated → gate ineffective | Precisely what calibration measures (FP rate by confidence bucket); D-3 escape hatch to independent validation |
| Line numbers drift between review and fix commits | Dedup/matching uses ±10-line bucket + category, not exact line |
| Context pack introduces prompt injection from target repos | §7 — untrusted-data framing; all control flow is Kotlin-side |
| Advisory mode lets real criticals through | Advisory still posts + notifies; per-project mode; graduation criteria in PRD §10 phase 3 |

## 12. Implementation Order (maps to PRD epics)

1. **Epic 18:** core types + `ReviewOutputParser` + runtime v2 + prompt v2 + metrics tables + persistence in orchestrator + baseline endpoint/script. Touches: `ClaudeReviewRuntime.kt`, `prompts/review.md`, `SqliteMetricsRepository.kt`, `AutoReviewOrchestrator.kt`, `DispatchService.kt`, `PrometheusMetricsBinder.kt`.
2. **Epic 19:** `ReviewEligibilityChecker` + `RiskRouter` + `StageAgentConfig.riskRules` + WORKFLOW.md keys.
3. **Epic 20:** `ReviewContextBuilder` + prompt variables + invariants template + PR-body requirement in `prompts/implement.md`.
4. **Epic 21:** `PublicationGate` + thresholds config + `review_mode` branch in `resolveReviewTargetState` + human-thread guard test.
5. **Epic 22:** label endpoint + `FindingOutcomeTracker` human path + calibration report (dashboard + scheduled notification).
6. **Epic 23:** specialist prompts + parallel fan-out + merge/dedup + optional independent validator.
