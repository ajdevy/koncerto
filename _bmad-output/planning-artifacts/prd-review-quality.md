# Product Requirements Document: Koncerto Review Quality (Signal-over-Noise Reviews)

**BMAD Phase:** Planning (PM)
**Date:** 2026-07-16
**Inputs:** `analysis-ai-review-usefulness.md`, Epic 16 (Claude Code Reviews, complete)
**Status:** Approved for solutioning — Architect phase complete, see `architecture-review-quality.md`

---

## 1. Executive Summary

Koncerto's AI review (Epic 16) works mechanically — review runs, gates, and a fix loop — but is unmeasured, unrouted, and context-starved. This initiative upgrades it into a measured, contract-driven review system: structured findings with telemetry, risk-based routing, bounded context, a publication gate, and a human-feedback calibration loop. The guiding metric is **cost-adjusted useful signal**, not comment volume.

## 2. Problem Statement

Every issue gets the identical single-agent review over a raw diff, with no domain context and no record of whether findings were useful. We cannot tell signal from noise, cannot tune anything, and risk the trust erosion that kills AI review adoption: noisy comments that humans learn to ignore.

## 3. Target Users

- **Project maintainers** running Koncerto against their repos — want fewer, higher-quality findings and evidence the review is worth its cost.
- **Coding agents** in the Needs Fix loop — want precise, actionable findings (bad findings send the fix agent chasing ghosts and burn attempts).
- **Human reviewers** at "Ready for Human Review" — want AI review to have already caught real defects, not to have buried them in noise.

## 4. Goals & Success Metrics

| Goal | Metric | Target |
|---|---|---|
| Measurable review | % of review runs with full finding-level metadata persisted | 100% after Epic 18 |
| High signal | High-evidence-outcome rate (finding → code change or explicit discussion) | ≥ 60% (baseline first; human benchmark ~72%) |
| Low noise | False-positive rate on published findings (from feedback labels) | < 20% |
| Cost awareness | Tokens + latency recorded per review run and per finding | 100% of runs |
| Right-sized effort | % of trivial/artifact-only diffs skipped by eligibility check | ≥ 95% of such diffs |
| Trust preserved | Human-initiated threads auto-resolved by agents | 0, enforced invariant |

**Baseline rule:** Epic 18 must run for ≥ 2 weeks (or ≥ 30 reviews) to establish baselines before gates/thresholds from Epic 21 are switched from advisory to enforcing.

## 5. Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-01 | Every review emits findings as structured data (JSON) with: category, severity, confidence, file:line, description, expected action, evidence/policy violated | P0 |
| FR-02 | Every review run persists run-level telemetry: issue id, commit SHA, model, prompt version, tokens, latency, verdict, attempt number | P0 |
| FR-03 | Finding outcomes are tracked: fixed (code change in Needs Fix loop), discussed, rejected, ignored → high-evidence-outcome rate computable | P0 |
| FR-04 | Eligibility check runs before review: artifact-only, generated, or docs-only diffs skip AI review with a logged skip reason | P0 |
| FR-05 | Risk router classifies each diff (low / standard / critical) using configurable critical-path globs (e.g. auth, payments, secrets handling) and diff stats; routing decision recorded | P1 |
| FR-06 | Context pack builder assembles bounded context for the reviewer: issue description + acceptance criteria, per-project domain invariants doc, changed files' neighborhood, relevant tests | P1 |
| FR-07 | Per-project `review-invariants.md` (domain rules the review must enforce) is supported and injected into review context | P1 |
| FR-08 | A validation stage scores raw findings (severity, confidence, actionability); only findings above a configurable publication threshold are posted; dropped findings are persisted with drop reason | P1 |
| FR-09 | One published comment per finding; counts and verdict never restated in multiple places (already partially in prompt contract — formalize) | P1 |
| FR-10 | Agents never resolve human-initiated PR/Linear threads; agent addresses a human comment only when explicitly tagged | P1 |
| FR-11 | Review mode per project/stage: `advisory` (findings posted, never blocks) vs `blocking` (current Needs Fix loop); default advisory for new projects | P1 |
| FR-12 | Critical-risk diffs run parallel specialist reviewers (security, reliability, architecture) whose findings are merged, deduped, and passed through the same validation gate | P2 |
| FR-13 | Human feedback on findings (accept/reject/false-positive) is capturable from PR review reactions or dashboard and stored against the finding | P2 |
| FR-14 | A calibration report aggregates feedback: FP rate by category/prompt-version/model, utility trend, threshold recommendations | P2 |

## 6. Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR-01 | Telemetry adds no review latency (async writes to `koncerto-metrics` SQLite) | < 50ms overhead |
| NFR-02 | Structured-output parse failures degrade gracefully to today's markdown-verdict path, flagged in telemetry | 0 pipeline stalls |
| NFR-03 | Prompt templates versioned (frontmatter); version recorded on every run so prompt changes are A/B comparable | 100% of runs |
| NFR-04 | Routing + eligibility add ≤ 1 cheap model call (or none — heuristics first) for low-risk diffs | cost-neutral for skipped diffs |
| NFR-05 | Multi-agent review reserved for critical routing tier; token budget per review run enforced with a hard cap | configurable cap |

## 7. Epics & Stories

Epic numbering continues from Epic 17 (ngrok). Sequencing is deliberate: **measure → route → contextualize → gate → learn → scale**.

### Epic 18: Review Telemetry & Structured Findings (P0, ~21 pts)

*Goal: make review output machine-readable and every run measurable. Prerequisite for everything else.*

#### Story 18.1: Structured findings contract in review prompt (5 pts)
- **As a** maintainer, **I want** the reviewer to emit findings as a JSON block (category, severity, confidence, file:line, description, expected action, evidence) alongside the human-readable verdict, **so that** findings become data, not prose.
- **AC:** `prompts/review.md` v2 emits a fenced `review-findings` JSON block; `ClaudeReviewRuntime` parses it; parse failure falls back to current verdict parsing and is flagged; prompt version in frontmatter recorded with the run.

#### Story 18.2: Review run telemetry in koncerto-metrics (5 pts)
- **As a** maintainer, **I want** every review run stored (issue, SHA, model, prompt version, tokens, latency, verdict, attempt), **so that** cost and behavior are observable.
- **AC:** new `review_runs` + `review_findings` tables in metrics SQLite; Prometheus counters/gauges for runs, findings by severity, tokens; written asynchronously; existing dashboards unaffected.

#### Story 18.3: Finding outcome tracking (8 pts)
- **As a** maintainer, **I want** each finding's outcome recorded (fixed in Needs Fix loop / discussed / rejected / ignored), **so that** high-evidence-outcome rate is computable.
- **AC:** fix-loop (`prompts/fix-review.md` + `.review-status` flow) reports per-finding disposition; outcome column updated; dashboard endpoint exposes HEO rate and signal-to-cost summary.

#### Story 18.4: Baseline report (3 pts)
- **As a** maintainer, **I want** a baseline snapshot after ≥30 reviews (comment volume, HEO rate, tokens per useful finding), **so that** later epics prove improvement against it.
- **AC:** `scripts/review-baseline.sh` or dashboard view producing the report; baseline stored in `_bmad-output/review-reports/baseline-*.md`.

### Epic 19: Eligibility & Risk Routing (P0/P1, ~13 pts)

*Goal: the cheapest comment is the one never generated.*

#### Story 19.1: Eligibility pre-check (5 pts)
- **AC:** before dispatching `ClaudeReviewRuntime`, orchestrator classifies the diff; artifact-only (`.koncerto/*`, `.review-*`), generated, lockfile-only, docs-only diffs skip review → direct `on_complete_state` with a logged skip reason and telemetry row. Removes the current in-prompt special-case.

#### Story 19.2: Risk router (8 pts)
- **AC:** `StageAgentConfig` gains `risk_rules` (critical-path globs, size thresholds); router outputs low/standard/critical recorded in telemetry; tier selects prompt variant and (later) multi-agent path; low tier uses a cheaper model; routing is heuristic-first, no extra LLM call for the low tier.

### Epic 20: Bounded Context Formation (P1, ~13 pts)

*Goal: fix the biggest quality lever — context — so findings stop being confidently wrong.*

#### Story 20.1: Context pack builder (8 pts)
- **AC:** review prompt receives: issue description + acceptance criteria (from Linear), list of changed components, contents of relevant neighboring files/tests (bounded by token budget), and project conventions; pack composition logged in telemetry.

#### Story 20.2: Per-project domain invariants doc (3 pts)
- **AC:** optional `review-invariants.md` in target repo (path configurable in WORKFLOW.md); injected into context pack; template documented; Koncerto's own repo gets one seeded from `_bmad/review/prompt-template.md` §2.

#### Story 20.3: PR description as review input (2 pts)
- **AC:** implementation prompt requires intent/risks/acceptance summary in PR body; review context includes it; missing description flagged as an input-quality signal in telemetry (not a blocking finding).

### Epic 21: Validation & Publication Gate (P1, ~13 pts)

*Goal: publish only high-signal findings; protect trust.*

#### Story 21.1: Validation/scoring stage (8 pts)
- **AC:** second one-shot pass (or same-call self-scoring rubric — Architect decides) assigns confidence + actionability to each raw finding; findings below `publication_threshold` (config) are persisted with `dropped` status and drop reason, never posted; thresholds configurable per severity.

#### Story 21.2: Advisory vs blocking mode (3 pts)
- **AC:** `review_mode: advisory|blocking` in stage config; advisory posts findings but always transitions to `on_complete_state`; blocking = current behavior; new projects default advisory; mode recorded in telemetry.

#### Story 21.3: Human-thread invariant (2 pts)
- **AC:** documented invariant + guard: no agent path resolves or replies to human-initiated PR/Linear threads unless the comment tags the agent; covered by a test on the comment-publishing path.

### Epic 22: Feedback & Calibration Loop (P2, ~13 pts)

#### Story 22.1: Feedback capture (5 pts)
- **AC:** 👍/👎 reactions on AI PR comments (or dashboard buttons) are ingested and stored as accept/reject/false-positive labels on findings.

#### Story 22.2: Calibration report (5 pts)
- **AC:** scheduled job produces per-period report: FP rate by category/prompt-version/model, HEO rate trend, tokens per useful finding, threshold recommendations; report lands in `_bmad-output/review-reports/calibration-*.md` and notification channel.

#### Story 22.3: Prompt/threshold iteration workflow (3 pts)
- **AC:** documented BMAD task for calibration review: sample dropped + rejected findings, adjust prompt/thresholds, bump prompt version; changes traceable in telemetry via prompt version (NFR-03).

### Epic 23: Multi-Agent Specialist Review for Critical Paths (P2, ~13 pts)

#### Story 23.1: Specialist reviewer prompts (5 pts)
- **AC:** security, reliability, architecture specialist prompts with narrow contracts (what each may flag); each emits the Epic 18 structured format.

#### Story 23.2: Parallel dispatch + merge/dedup (8 pts)
- **AC:** critical-tier reviews fan out specialists in parallel, merge findings, dedup by file:line+category, feed the Epic 21 gate; token budget cap enforced (NFR-05); wall-clock and cost recorded.

## 8. Out of Scope

- Replacing the human review stage ("Ready for Human Review" remains).
- IDE/local-first review plugin (hybrid model noted in analysis §1.6; Koncerto is the centralized side — a local mode may be a future epic).
- Auto-fix beyond the existing Needs Fix loop; auto-merge.
- Fine-tuning models; this initiative tunes prompts, routing, thresholds only.
- Style/lint findings — permanently out of the review contract (linters own that).

## 9. Open Questions — RESOLVED (Architect phase, 2026-07-16)

All five resolved in `architecture-review-quality.md` §10 (Technical Decisions):

1. Validation stage → self-scoring rubric + deterministic Kotlin gate now; independent validator pass reserved as critical-tier option in Epic 23 (D-3).
2. Finding outcomes → hybrid: fix-agent self-report (`.review-fix-report.json`) + re-review corroboration + human labels override (D-4).
3. Feedback surface → dashboard-first; PR comment markers keep GitHub-reaction polling possible later (D-5).
4. Token accounting → `claude --print --output-format json` envelope; usage/duration for free, fallback to plain parsing (D-1).
5. Per-tier models → config support now, defaults identical until baseline data exists (D-6).

## 10. Rollout & Sequencing

1. **Phase 1 (Epic 18):** instrument only — zero behavior change. Collect ≥ 30 reviews of baseline.
2. **Phase 2 (Epics 19–20):** cut waste, fix context. Compare against baseline.
3. **Phase 3 (Epic 21):** gate in advisory mode; flip to enforcing once FP rate < 20% on ≥ 2 weeks of data.
4. **Phase 4 (Epics 22–23):** close the loop; enable multi-agent only for the critical tier and only if Phase 2/3 data shows the generic reviewer under-catching on critical paths.
