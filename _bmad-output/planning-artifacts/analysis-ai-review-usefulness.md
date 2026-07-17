# Analysis: Useful AI Code Review for Koncerto

**BMAD Phase:** Analysis (Analyst)
**Date:** 2026-07-16
**Source:** Podlodka AI Club session "Как настроить полезный AI Code Review" — Кирилл Филимонов (@kirillf)
**Status:** Input to PRD `prd-review-quality.md`

---

## 1. Transcript Analysis — Key Theses

### 1.1 Measuring usefulness: signal-to-cost

- **Utility = useful signal / total cost.** Useful signal = actual defects, security issues, violated invariants — not speculation. Cost = tokens + human attention + delivery delay + trust erosion from false positives + system maintenance.
- More comments ≠ more useful findings. In the speaker's data, AI review produced **2.5× more comments** than human review, but only **44% reached a "high evidence outcome"** vs **72% for humans**.
- **High evidence outcome** = the finding led to a code change OR an explicit discussion (not silent thread closure). This beats raw resolution metrics, which are gamed by auto-resolve and polite closures.
- Metrics hierarchy (weakest → strongest): input quality → activity (comment volume) → behavioral proxy (code changes) → semantic signals (severity, violated invariant) → flow metrics (cycle time, merge rate) → product outcomes (escaped defects, incidents) → **cost-adjusted utility**.

### 1.2 Review contract

A review contract specifies scope, expected outcome, publishable signal types, evidence standards, and boundaries. High-signal findings: clear logic errors, security issues, explicit rule violations. Explicitly NOT publishable: style nits, anything a linter can check, uncertain/subjective suggestions.

### 1.3 Routing

- Not every PR deserves the same review. Exclude trivial/generated changes entirely; lightweight review for low-risk; heavy context + multi-agent for mission-critical (auth, payments, security).
- "The cheapest comment is the one that was never generated unnecessarily."

### 1.4 Context and domain invariants

- A generic agent without context produces confidently wrong findings (speaker's example: flagging `verified_domains` as unused and proposing to reject all external domains; a context-aware agent inferred the real policy from docs).
- **Bounded context** = task/intent, diff, nearby code, relevant docs and tests, constraints — sufficient but not bloated.
- The PR description is part of the review **input**, not a formality: intent, risks, acceptance criteria.

### 1.5 Multi-agent workflow with publication gate

Pipeline: eligibility check → discovery (docs, domain files, local rules) → summary/risk level → parallel specialist agents (security, reliability, product, architecture) → **validation & scoring** (severity, confidence, expected action, policy alignment) → **publication gate** — only findings above threshold are posted; the rest are logged, not published. False positives corrode trust and waste reviewer time.

### 1.6 Local-first vs centralized

Local-first: fast feedback, but invisible (no audit trail, no cost tracking, siloed learning). Centralized: observable, benchmarkable, org-wide learning, but slower. Recommended hybrid: run locally with attribution, post results to a shared space.

### 1.7 Learning loop

Mature cycle: submission & scope → context formation → focused agent review under a contract → scoring & validation → human feedback (accept/reject/discuss; **never auto-resolve human-initiated threads**) → **calibration** (analyze feedback patterns to refine prompts, thresholds, routing, model selection).

A mature workflow **writes metadata at generation time**: finding category, severity, confidence, policy violated, expected action, tokens spent, latency.

### 1.8 Guardrails checklist (verbatim recommendations)

1. One comment per one issue.
2. Explicit publication gate — not all findings get posted.
3. Agent must never auto-resolve human-created threads; tag human comments if the agent should address them.
4. Start in **advisory (non-blocking) mode**; graduate to blocking only after a precision baseline is achieved.
5. Establish a baseline before changing anything; instrument every review run (attach to commit, preserve metadata, count tokens and duration).
6. Calibration loop: sample comments, identify false positives, refine prompts.

---

## 2. Current State: Koncerto Review Pipeline (Epic 16, complete)

| Component | What exists today |
|---|---|
| Trigger | Linear "In Review" state polled by orchestrator; `ClaudeReviewRuntime` runs `claude --print` one-shot (WORKFLOW.md stage config) |
| Prompt | Single generic prompt `prompts/review.md`: reviews `git diff HEAD~1`, 6 fixed categories, verdict line ✅/❌ + counts + finding tables |
| Gate | Verdict parsed from stdout → `.review-status` (pass/fail); fail → "Needs Fix" (opencode fixes from report), max 3 attempts then force-complete |
| Output | Markdown reports in `_bmad-output/review-reports/`; PR comment via `AutoReviewOrchestrator` + gh CLI |
| Human step | After AI pass → "Ready for Human Review" (`prompts/human-review.md`) |
| Metrics | `koncerto-metrics` (SQLite + Prometheus) exists, but review runs record no finding-level metadata, tokens, or outcomes |

## 3. Gap Analysis (transcript best practice vs Koncerto today)

| # | Best practice | Koncerto today | Gap severity |
|---|---|---|---|
| G1 | Metadata at generation time (category, severity, confidence, tokens, latency per finding) | Free-form markdown verdict; only pass/fail is machine-read | **High — blocks all measurement** |
| G2 | High-evidence-outcome tracking; signal-to-cost utility | No outcome tracking at all | High |
| G3 | Risk-based routing + eligibility check | Every issue gets the identical review; only pipeline-artifact diffs are special-cased inside the prompt | High |
| G4 | Bounded context: intent, invariants, docs, nearby code | Prompt gets only issue id/title + raw diff; no domain invariants, no acceptance criteria, no docs | High |
| G5 | Validation stage + publication gate | Reviewer's own verdict is published directly; no second-stage scoring, no threshold | Medium-High |
| G6 | Multi-agent specialists for critical paths | Single generic agent for everything | Medium |
| G7 | Human feedback capture + calibration loop | None; fix-loop consumes findings but nothing learns from accept/reject | Medium |
| G8 | Advisory→blocking progression | Review is already blocking (Needs Fix loop) with no measured precision baseline | Medium |
| G9 | Never auto-resolve human threads | Not applicable yet (no thread management), but must become an invariant before comment threads are managed | Low (preventive) |

**Strengths to keep:** the state-machine integration (In Review / Needs Fix / max-attempts), report persistence, PR comment publishing, and the existing metrics module are exactly the "centralized observability" substrate the talk recommends — they just carry no review telemetry yet.

## 4. Recommendation

Sequence the work **measurement-first** (you can't improve what you can't see), then reduce noise (routing + context + gate), then close the loop (feedback + calibration), then scale up (multi-agent) only where risk justifies cost. Detailed in `prd-review-quality.md`.

## 5. References

- Session transcript: https://ai-club-bot.podlodka.io/wiki/pages/77
- Papers cited in the talk: arXiv 2605.02273, 2603.11078, 2602.14611, 2603.23448, 2605.17548
- github.com/kirillF/access-review-lab
