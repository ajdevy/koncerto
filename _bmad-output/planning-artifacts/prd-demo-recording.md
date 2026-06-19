---
stepsCompleted: ["step-01-init", "step-02-discovery", "step-02b-vision", "step-02c-executive-summary", "step-03-success", "step-04-journeys"]
inputDocuments:
  - "_bmad-output/planning-artifacts/project-brief.md"
  - "_bmad-output/planning-artifacts/prd.md"
  - "_bmad-output/planning-artifacts/architecture.md"
  - "_bmad/brainstorming/brainstorming-session-2026-06-18.md"
workflowType: 'prd'
project_name: "Koncierto - Demo Recording Feature"
user_name: "aj"
date: "2026-06-18"
documentCounts:
  briefCount: 1
  researchCount: 0
  brainstormingCount: 1
  projectDocsCount: 3
classification:
  projectType: "developer_tool"
  domain: "general"
  complexity: "medium"
  projectContext: "brownfield"
---

# Product Requirements Document - Koncierto Demo Recording Feature

**Author:** aj
**Date:** 2026-06-18

## Executive Summary

**Koncierto Demo Recording** adds automated visual verification to the Koncierto AI orchestration platform. After an AI agent completes a task and code review passes, the system automatically records an end-to-end demonstration — screen video for UX features, structured HTML logs for technical tasks — and posts a presigned Cloudflare R2 link to the Linear ticket. Reviewers verify correctness in 2 minutes without launching code or manual testing.

**Target Users:**
- **Engineering Leads** — Trust AI-generated work without manual verification overhead
- **Developers** — Reduce context switching; see what agents built instantly
- **DevOps** — Audit trail of automated deployments and API integrations

**Problem Solved:** AI agents produce code changes, but human reviewers lack efficient visual verification. Manual testing is slow; PR descriptions lack behavioral context; Loom videos require extra effort. The orchestration system already holds all execution context (task, code, tests, deployments) — this feature surfaces it automatically.

**Core Insight:** The orchestration layer is the single source of truth for what happened. Demo recording is not a separate process — it's the natural visual output of the existing workflow.

## What Makes This Special

**Zero-Effort Verification:** See changes without launching code, running tests, or manual recording. The demo *is* the workflow output.

**Thorough by Default:** Captures more detail than human demos — exact commands, API calls, timestamps, reproduction steps, AI-generated timeline with explanations.

**Integrated, Not Bolted On:** Triggered by existing "code review passed" event. Uses existing Linear integration, workspace isolation, retry logic, and YAML workflow config. opencode free models (configurable in `workflow.md`) generate timelines and reproduction instructions.

**Space-Efficient:** 720p@10fps VP9 encoding (~2-5 MB/min). Rolling window retention in R2 (10 GB free tier) — auto-deletes oldest when space needed. Long-lived presigned URLs for sharing.

**Resilient:** Exponential backoff retry (3 attempts). Marks Linear ticket "blocked" if all retries fail. Fallback: technical tasks use asciinema text recording (~10 KB vs 50 MB video).

## Project Classification

- **Project Type:** `developer_tool` — Orchestration service, CLI/API for AI agent workflows
- **Domain:** `general` / `scientific` — AI/ML, developer productivity, automation
- **Complexity:** `medium` — Multi-platform recording (web/mobile/desktop/terminal), AI subprocess management, cloud storage integration; no regulatory constraints
- **Project Context:** `brownfield` — New feature added to existing Koncierto platform (Kotlin/Spring Boot, Linear + Codex/opencode integration, modular architecture)

## Success Criteria

### User Success

- **Verification Speed:** Reviewer verifies demo in < 2 minutes (watch video or read HTML report)
- **Confidence Level:** > 90% of demos give reviewers "high confidence" without manual testing
- **Task Coverage:** Usable demos generated for > 80% of completed tasks (UX + technical)
- **Zero Friction:** Demo appears automatically in Linear ticket — no setup, no manual steps

### Business Success

- **Adoption Rate:** > 80% of completed tasks have demos generated and viewed
- **Time Savings:** Estimated > 5 hours/week/team saved on manual verification
- **Trust Metric:** > 50% reduction in "re-run locally" or "manual demo requested" comments
- **Reviewer Satisfaction:** > 4/5 survey score on "Demo helped me verify faster"

### Technical Success

- **Generation Reliability:** > 95% demo generation success rate (after retries)
- **Storage Efficiency:** Average demo size < 50 MB (720p@10fps VP9 encoding)
- **Cost Control:** R2 storage stays within free tier (10 GB) for typical team usage
- **Latency:** Demo available in Linear < 5 minutes after code review passes
- **Cross-Platform Coverage:** Web (Playwright), Terminal (asciinema) in MVP; Android, iOS, Desktop (Linux/macOS/Windows) in Growth

### Measurable Outcomes

| Metric | Target | Measurement |
|--------|--------|-------------|
| Demo generation success rate | > 95% | (Successful uploads) / (Total attempts) |
| Average demo file size | < 50 MB | R2 object metadata |
| Time from review pass → Linear comment | < 5 min | Timestamp diff |
| Reviewer verification time | < 2 min | Survey / analytics |
| Tasks with usable demo | > 80% | (Tasks with demo) / (Completed tasks) |
| R2 storage used | < 8 GB | R2 dashboard |
| Retry recovery rate | > 60% | (Succeeded on retry) / (Failed first) |

## Product Scope

### MVP - Minimum Viable Product

- [ ] Trigger on PR merge + "demo" label
- [ ] Web recording (Playwright) → R2 → Linear comment
- [ ] Terminal recording (asciinema) → R2 → Linear comment
- [ ] 720p@10fps VP9 encoding
- [ ] R2 rolling window cleanup (space-based)
- [ ] Exponential backoff retry (3x)
- [ ] Mark Linear ticket blocked on total failure
- [ ] HTML report: video link + timeline + repro steps
- [ ] opencode free model config in workflow.md

### Growth Features (Post-MVP)

- [ ] Android recording (adb screenrecord)
- [ ] iOS recording (xcrun simctl)
- [ ] Desktop recording (Linux/macOS/Windows via ffmpeg)
- [ ] AI-generated timeline (opencode model)
- [ ] AI-generated reproduction steps
- [ ] Presigned URL expiry config
- [ ] Demo analytics dashboard

### Vision (Future)

- [ ] Live demo streaming during agent execution
- [ ] Interactive demo (clickable timeline → code location)
- [ ] Demo comparison (before/after for same feature)
- [ ] Team demo library with search
- [ ] Integration with GitHub PR reviews (not just Linear)