---
stepsCompleted: ["step-01-init", "step-02-discovery", "step-02b-vision", "step-02c-executive-summary", "step-03-success", "step-04-journeys", "step-05-domain", "step-07-project-type", "step-08-scoping", "step-09-functional", "step-10-nonfunctional", "step-11-polish"]
releaseMode: phased
inputDocuments:
  - "_bmad-output/planning-artifacts/project-brief.md"
  - "_bmad-output/planning-artifacts/prd.md"
  - "_bmad-output/planning-artifacts/architecture.md"
  - "_bmad/brainstorming/brainstorming-session-2026-06-18.md"
workflowType: 'prd'
project_name: "Koncerto - Demo Recording Feature"
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

# Product Requirements Document — Koncerto Demo Recording Feature

**Author:** aj
**Date:** 2026-06-18

**Project Classification:** `developer_tool` / `brownfield` / `medium` — New feature for Koncerto (Kotlin/Spring Boot, modular architecture with Linear + opencode integration). Multi-platform recording, cloud storage integration, no regulatory constraints.

## Executive Summary

Koncerto Demo Recording adds automated visual verification to the Koncerto AI orchestration platform. After an AI agent completes a task and code review passes, the system records an end-to-end demonstration — screen video for UX features, structured HTML logs for technical tasks — and posts a presigned Cloudflare R2 link to the Linear ticket. Reviewers verify correctness in 2 minutes without launching code or manual testing.

**Target Users:**
- **Engineering Leads** — Trust AI-generated work without manual verification overhead
- **Developers** — Reduce context switching; see what agents built instantly
- **DevOps** — Audit trail of automated deployments and API integrations

**Problem:** AI agents produce code changes, but human reviewers lack efficient visual verification. Manual testing is slow; PR descriptions lack behavioral context; Loom videos require extra effort. The orchestration system already holds all execution context — this feature surfaces it automatically.

**Core Insight:** The orchestration layer is the single source of truth for what happened. Demo recording is not a separate process — it is the natural visual output of the existing workflow.

## What Makes This Special

**Zero-Effort Verification:** See changes without launching code, running tests, or manual recording. The demo *is* the workflow output.

**Thorough by Default:** Captures more detail than human demos — exact commands, API calls, timestamps, reproduction steps, AI-generated timeline with explanations.

**Integrated, Not Bolted On:** Triggered by existing "code review passed" event. Uses existing Linear integration, workspace isolation, retry logic, and YAML workflow config. opencode free models (configurable in `workflow.md`) generate timelines and reproduction instructions.

**Space-Efficient:** 720p@10fps VP9 encoding (~2-5 MB/min). Rolling window retention in R2 (10 GB free tier) — auto-deletes oldest when space needed. Long-lived presigned URLs for sharing.

**Resilient:** Exponential backoff retry (3 attempts). Marks Linear ticket "blocked" if all retries fail. Fallback: technical tasks use asciinema text recording (~10 KB vs 50 MB video).

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
| Time from review pass to Linear comment | < 5 min | Timestamp diff |
| Reviewer verification time | < 2 min | Survey / analytics |
| Tasks with usable demo | > 80% | (Tasks with demo) / (Completed tasks) |
| R2 storage used | < 8 GB | R2 dashboard |
| Retry recovery rate | > 60% | (Succeeded on retry) / (Failed first) |

## Product Scope

### Strategy

**MVP Philosophy:** Problem-solving MVP — demonstrate enough recording capability so reviewers can verify correctness without manual testing. Web + Terminal covers the majority of Koncerto task types.

**Core Question:** Can a reviewer verify an AI-completed task without launching code or running manual tests?

**User Journeys Supported in MVP:** Engineering Lead (Trust But Verify), Developer (Reproduce & Learn), DevOps (Observe & Optimize — basic metrics).

### MVP (Phase 1)

- Trigger on review passed + "demo" label
- Web recording (Playwright) to R2 to Linear comment
- Terminal recording (asciinema) to R2 to Linear comment
- 720p@10fps VP9 encoding
- R2 rolling window cleanup (space-based)
- Exponential backoff retry (3 attempts)
- Mark Linear ticket "blocked" on total failure
- HTML report: video link + timeline + repro steps
- opencode free model config in workflow.md

### Growth (Phase 2)

- Android recording (adb screenrecord)
- iOS recording (xcrun simctl)
- Desktop recording (Linux/macOS/Windows via ffmpeg)
- AI-generated timeline (opencode model)
- AI-generated reproduction steps
- Demo analytics dashboard
- Presigned URL expiry config

### Vision (Phase 3)

- Live demo streaming during agent execution
- Interactive demo (clickable timeline to code location)
- Demo comparison (before/after for same feature)
- Team demo library with search
- GitHub PR review integration

### Risk Mitigation

**Technical:**
- Cross-platform reliability: MVP limits to web + terminal (most stable). Mobile/Desktop deferred to Phase 2.
- Video size: 720p@10fps VP9 target < 50 MB. Auto-reencode if exceeded.
- R2 quota: Pre-flight space check before recording. Rolling delete prevents upload failure.

**Adoption:**
- Reviewer willingness: Target < 2 min watch time. Timeline + repro steps enable quick scanning.
- Task coverage gap: Technical tasks get HTML logs (asciinema fallback).

**Resource:**
- Runner overhead: Recording adds CPU/memory. Monitor and limit concurrency.
- opencode model availability: Configurable fallback — generate HTML without AI timeline if model is down.

## User Journeys

### Journey 1: Engineering Lead — "Trust But Verify"

**Persona:** Sarah, Engineering Lead at a 20-person startup. Responsible for delivery velocity and code quality. Tired of manually pulling branches, running tests, recording Looms.

**Opening Scene:** Friday 4 PM. 3 PRs from Koncerto agents ready for review.

**Rising Action:** Opens first PR. Code review passes. Linear comment appears: "Demo: https://r2.koncierto.demo/task-452.webm — 2 min watch". Clicks. 720p video loads instantly. Timeline: "0:00 App launch → 0:15 Login → 0:45 New dashboard widget → 1:30 Data refresh → 2:00 Settings persist". Watches at 2x. Confident. Approves.

**Climax:** Third PR is backend API — no UI. Linear comment: "Demo: https://r2.koncierto.demo/task-453.html". Opens HTML: structured curl commands, responses, deployment steps, reproduction instructions. Sees exactly what was deployed.

**Resolution:** All 3 PRs merged in 15 minutes. No manual testing.

### Journey 2: Developer — "Reproduce & Learn"

**Persona:** Marcus, Senior Developer. Picks up a task the agent completed yesterday.

**Opening Scene:** Opens Linear CON-452. Status: "Done — Demo recorded".

**Rising Action:** Watches agent implement: file edits, test runs, refactoring. Timeline shows agent fix a race condition he did not anticipate.

**Climax:** HTML report has "Reproduction Steps" section. Runs them locally. Works. He now understands the feature better than if he had written it.

**Resolution:** Extends widget in 30 min instead of 2 hrs. Comments: "Great pattern at 1:15 — reused for new feature."

### Journey 3: DevOps Engineer — "Observe & Optimize"

**Persona:** Priya, DevOps Engineer. Owns Koncerto deployment.

**Opening Scene:** Opens Grafana. Sees: "Demo Generation: 97% success (234/241)". "R2 Storage: 3.2 GB / 10 GB". "Avg Demo: 18 MB".

**Rising Action:** Drills into 7 failures. 5 recovered on retry 2. 2 marked Linear "blocked". One error: "ffmpeg: encoder not found" on new runner image.

**Climax:** Fixes runner image. Re-triggers via Linear "Record Demo". Pipeline: recording → encoding → R2 upload → Linear comment. All green.

**Resolution:** Adds alert: "Demo failure rate > 5% for 1h → page on-call".

### Journey 4: On-Call Support — "Recover & Communicate"

**Persona:** Alex, On-call engineer. Gets paged: "Demo generation failed for CON-501".

**Opening Scene:** 2 AM. Opens Linear CON-501. Red banner: "Demo recording failed after 3 attempts". Error: "R2 upload timeout".

**Rising Action:** Checks R2 — responding. Runner logs — intermittent network blip. Clicks "Record Demo" in Linear.

**Climax:** Recording completes. Upload succeeds. Linear comment: "Demo ready for review."

**Resolution:** Reviewer approves. Zero handoff.

### Journey 5: New Team Member — "Learn from History"

**Persona:** Jordan, New hire, Week 2.

**Opening Scene:** Assigned to extend "dashboard widgets". Searches Linear for "dashboard widget".

**Rising Action:** Finds 5 completed tasks with demos. Watches 2-min video of agent building the widget system. Reads HTML report: architecture, API patterns, tests.

**Climax:** "Reproduction Steps" — runs locally. Works. Understands pattern: WidgetRegistry → WidgetFactory → WidgetComponent.

**Resolution:** Ships first feature Day 3. "The demos taught me more than docs."

### Journey Requirements Summary

| Journey | Core Capabilities Needed |
|---------|--------------------------|
| **Engineering Lead** | Auto-trigger, video+HTML, timeline, Linear comment, presigned URL |
| **Developer** | AI timeline, reproduction steps, code context in report |
| **DevOps** | Metrics (success, storage, size, retries), rolling cleanup, manual re-trigger |
| **Support** | Blocked status, error details, manual re-trigger, audit trail |
| **New Hire** | Demo search, historical access, reproduction as tutorial |

## Functional Requirements

### Demo Recording

- FR1: System can record an end-to-end screen video of a completed AI agent task
- FR2: System can record a terminal session (commands + output) for technical tasks
- FR3: System can specify recording platform per task (web, terminal, mobile, desktop)
- FR4: System can encode screen recordings at minimal readable quality (720p@10fps VP9)
- FR5: System can capture mouse cursor and click indicators during recording
- FR6: System can apply dirty-rectangle optimization to skip static frames
- FR7: System can add timestamp overlay to video recording
- FR8: System can record Application Under Test on Android devices via ADB
- FR9: System can record Application Under Test on iOS devices via xcrun
- FR10: System can record Application Under Test on desktop (Linux/macOS/Windows)

### Report Generation

- FR11: System can generate an HTML report for each recorded demo
- FR12: HTML report embeds or links to the recorded video
- FR13: HTML report includes a text timeline of events with timestamps
- FR14: HTML report includes step-by-step reproduction instructions
- FR15: HTML report includes structured logs of API calls and commands for technical tasks
- FR16: System can generate timeline annotations using configured AI model
- FR17: System can generate reproduction steps using configured AI model

### Storage & Management

- FR18: System can upload demo artifacts to Cloudflare R2 object storage
- FR19: System can generate long-lived presigned URLs for demo artifacts
- FR20: System can check available storage quota before recording
- FR21: System can delete oldest demo artifacts when storage space is needed
- FR22: System can tag R2 objects with task metadata (task ID, commit SHA, PR number, platform)
- FR23: System can "keep" specific demos to exclude them from rolling window cleanup

### Integration

- FR24: System can trigger demo recording when a PR review passes
- FR25: System can trigger demo recording via a Linear "Record Demo" action
- FR26: System can post a comment with the demo URL to the Linear ticket
- FR27: System can update Linear ticket status to "blocked" on total demo failure
- FR28: System can register demo recording as a configurable post-code-review workflow step
- FR29: System can pass task metadata (platform, app, test script) from Linear to the recorder

### Error Handling & Resiliency

- FR30: System can retry failed demo recording with exponential backoff (up to 3 attempts)
- FR31: System can validate recording integrity after stop (duration, size, corruption check)
- FR32: System can fall back to asciinema text recording when video recording fails
- FR33: System can report detailed error information on demo generation failure
- FR34: System can recover from partial recordings on platform disconnection

### Observability

- FR35: System can expose metrics: demo generation success rate, retry count, failure reasons
- FR36: System can expose metrics: R2 storage usage, average demo size, cleanup events
- FR37: System can expose metrics: demo generation latency (review pass to Linear comment)
- FR38: System can log detailed audit trail of recording, upload, and Linear comment events

### Configuration

- FR39: System can configure recording quality (resolution, fps, codec) via YAML workflow
- FR40: System can configure AI model (opencode free) and features (timeline, repro steps) via YAML workflow
- FR41: System can configure R2 endpoint, bucket, and credentials via environment or workflow
- FR42: System can configure retry policy (max attempts, backoff strategy) via YAML workflow
- FR43: System can configure trigger conditions (review passed, label present, manual) via YAML workflow
- FR44: System can enable or disable demo recording per workflow in YAML config

## Non-Functional Requirements

### Performance

- NFR1: Recording setup and teardown complete within 10 seconds
- NFR2: Demo encoding completes within 2x the recording duration
- NFR3: R2 upload completes within 30 seconds for average recording (< 50 MB)
- NFR4: Recording does not increase total task run time by more than 50%
- NFR5: HTML report generation completes within 5 seconds

### Security

- NFR6: Presigned URLs are scoped to individual artifact and cannot enumerate bucket
- NFR7: R2 credentials stored as secret, not inline in workflow config
- NFR8: Linear API token stored as secret with minimum required permissions
- NFR9: Presigned URLs reference opaque identifiers, not internal task IDs or file paths
- NFR10: Recorded artifacts are automatically removed when Linear ticket is archived

### Scalability

- NFR11: System supports up to 3 concurrent demo recordings without runner degradation
- NFR12: Rolling window cleanup maintains R2 usage below 9 GB (90% of 10 GB free tier)
- NFR13: Demo artifacts are deletable without impacting task execution

### Integration

- NFR14: Linear API failures do not block the demo generation pipeline — fall back to local report
- NFR15: R2 API errors trigger retry with exponential backoff (3 attempts)
- NFR16: Recording tool availability check runs before task execution begins

## Developer Tool Specific Requirements

### Installation & Integration (Brownfield)

- Integrated as new Koncerto module: depends on `koncerto-linear`, `koncerto-agent`, `koncerto-workspace`
- No new external dependencies — uses existing Koncerto infrastructure (Spring Boot, Kotlin coroutines, YAML config)
- opencode free model config in `workflow.md` (model selection, API endpoint, token limits)

### API Surface

- **Trigger:** Webhook/event on PR merge + "demo" label (existing Koncerto event system)
- **Manual trigger:** Linear "Record Demo" action (existing `koncerto-linear` integration)
- **Storage:** Cloudflare R2 S3-compatible API (`aws s3 cp` or S3 SDK)
- **Output:** Presigned R2 URL posted as Linear comment (existing Linear GraphQL client)
- **Metadata:** R2 object tags: `task_id`, `commit_sha`, `pr_number`, `platform`, `type` (video/html)

### Configuration (workflow.yaml)

```yaml
demo_recording:
  enabled: true
  trigger: review_passed
  platform:
    web: playwright
    terminal: asciinema
  quality:
    resolution: 1280x720
    fps: 10
    codec: vp9
  storage:
    r2_endpoint: ${R2_ENDPOINT}
    r2_bucket: koncerto-demos
  ai:
    model: opencode-free
    timeline: true
    repro_steps: true
  retry:
    max_attempts: 3
    backoff: exponential
  error:
    on_failure: mark_blocked
```

### Documentation & User Guide

- **Setup:** R2 bucket configuration, Linear API permissions, opencode model config
- **Usage:** Add "demo" label to PR, or use Linear "Record Demo" action
- **Verification:** Watch video / read HTML report / run reproduction steps
- **Troubleshooting:** Failed generation → check runner logs; R2 quota → rolling cleanup handles it; missing dependencies → check runner image