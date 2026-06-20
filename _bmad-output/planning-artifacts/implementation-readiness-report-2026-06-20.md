---
stepsCompleted: ["step-01-document-discovery"]
assessmentDate: "2026-06-20"

assessor: "aj"
project: "Koncerto - Demo Recording Feature"
---

# Implementation Readiness Assessment — Koncerto Demo Recording Feature

## Document Inventory

### PRD Documents (Selected)

- `prd-demo-recording.md` — Demo Recording Feature PRD (3.5 KB, 2026-06-20)

### Architecture Documents

- `architecture.md` — Existing Koncerto architecture

### Project Brief

- `project-brief.md` — Existing Koncerto project brief

### Other Documents

- `prd.md` — Original Koncerto main PRD (not used for this assessment)

### Missing Documents (Not Assessed)

- No Epics documents exist — will skip epic coverage validation
- No UX documents exist — will note if implied
- No Stories exist — will note

## PRD Analysis

### Functional Requirements

**Demo Recording (FR1-FR10)**
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

**Report Generation (FR11-FR17)**
- FR11: System can generate an HTML report for each recorded demo
- FR12: HTML report embeds or links to the recorded video
- FR13: HTML report includes a text timeline of events with timestamps
- FR14: HTML report includes step-by-step reproduction instructions
- FR15: HTML report includes structured logs of API calls and commands for technical tasks
- FR16: System can generate timeline annotations using configured AI model
- FR17: System can generate reproduction steps using configured AI model

**Storage & Management (FR18-FR23)**
- FR18: System can upload demo artifacts to Cloudflare R2 object storage
- FR19: System can generate long-lived presigned URLs for demo artifacts
- FR20: System can check available storage quota before recording
- FR21: System can delete oldest demo artifacts when storage space is needed
- FR22: System can tag R2 objects with task metadata (task ID, commit SHA, PR number, platform)
- FR23: System can "keep" specific demos to exclude them from rolling window cleanup

**Integration (FR24-FR29)**
- FR24: System can trigger demo recording when a PR review passes
- FR25: System can trigger demo recording via a Linear "Record Demo" action
- FR26: System can post a comment with the demo URL to the Linear ticket
- FR27: System can update Linear ticket status to "blocked" on total demo failure
- FR28: System can register demo recording as a configurable post-code-review workflow step
- FR29: System can pass task metadata (platform, app, test script) from Linear to the recorder

**Error Handling & Resiliency (FR30-FR34)**
- FR30: System can retry failed demo recording with exponential backoff (up to 3 attempts)
- FR31: System can validate recording integrity after stop (duration, size, corruption check)
- FR32: System can fall back to asciinema text recording when video recording fails
- FR33: System can report detailed error information on demo generation failure
- FR34: System can recover from partial recordings on platform disconnection

**Observability (FR35-FR38)**
- FR35: System can expose metrics: demo generation success rate, retry count, failure reasons
- FR36: System can expose metrics: R2 storage usage, average demo size, cleanup events
- FR37: System can expose metrics: demo generation latency (review pass to Linear comment)
- FR38: System can log detailed audit trail of recording, upload, and Linear comment events

**Configuration (FR39-FR44)**
- FR39: System can configure recording quality (resolution, fps, codec) via YAML workflow
- FR40: System can configure AI model (opencode free) and features (timeline, repro steps) via YAML workflow
- FR41: System can configure R2 endpoint, bucket, and credentials via environment or workflow
- FR42: System can configure retry policy (max attempts, backoff strategy) via YAML workflow
- FR43: System can configure trigger conditions (review passed, label present, manual) via YAML workflow
- FR44: System can enable or disable demo recording per workflow in YAML config

**Total FRs: 44**

### Non-Functional Requirements

**Performance (NFR1-NFR5)**
- NFR1: Recording setup and teardown complete within 10 seconds
- NFR2: Demo encoding completes within 2x the recording duration
- NFR3: R2 upload completes within 30 seconds for average recording (< 50 MB)
- NFR4: Recording does not increase total task run time by more than 50%
- NFR5: HTML report generation completes within 5 seconds

**Security (NFR6-NFR10)**
- NFR6: Presigned URLs are scoped to individual artifact and cannot enumerate bucket
- NFR7: R2 credentials stored as secret, not inline in workflow config
- NFR8: Linear API token stored as secret with minimum required permissions
- NFR9: Presigned URLs reference opaque identifiers, not internal task IDs or file paths
- NFR10: Recorded artifacts are automatically removed when Linear ticket is archived

**Scalability (NFR11-NFR13)**
- NFR11: System supports up to 3 concurrent demo recordings without runner degradation
- NFR12: Rolling window cleanup maintains R2 usage below 9 GB (90% of 10 GB free tier)
- NFR13: Demo artifacts are deletable without impacting task execution

**Integration (NFR14-NFR16)**
- NFR14: Linear API failures do not block the demo generation pipeline — fall back to local report
- NFR15: R2 API errors trigger retry with exponential backoff (3 attempts)
- NFR16: Recording tool availability check runs before task execution begins

**Total NFRs: 16**

### Additional Requirements (from Developer Tool Specific)

- Integration as new Koncierto module: depends on `koncerto-linear`, `koncerto-agent`, `koncerto-workspace`
- No new external dependencies — uses existing Koncierto infrastructure
- opencode free model config in `workflow.md`
- Webhook/event trigger on PR merge + "demo" label
- Manual trigger via Linear "Record Demo" action
- Cloudflare R2 S3-compatible API storage
- Presigned R2 URL posted as Linear comment
- R2 object tags: task_id, commit_sha, pr_number, platform, type

### PRD Completeness Assessment

The PRD is comprehensive and follows BMAD standards well:

- **Strengths:** Full traceability chain (vision → success criteria → user journeys → FRs → NFRs), measurable success criteria with targets, clear scope phasing, detailed API surface and config example
- **Spelling issue:** Document uses "Koncierto" consistently but user confirmed correct name is "Koncerto" — should be corrected
- **Coverage:** All major capability areas have dedicated FR sections. 44 FRs and 16 NFRs is appropriate for medium-complexity feature
- **Actionability:** Requirements are implementation-agnostic and testable
- **Missing:** No screenshots fallback explicitly called out (brainstorming mentioned this); no story/epics documents yet to validate against

## Epic Coverage Validation

### Coverage Matrix

| FR Number | PRD Requirement | Epic Coverage | Status |
|-----------|----------------|---------------|--------|
| FR1–FR44 | All 44 FRs from PRD | **NO EPICS EXIST** | ⚠️ Pending |

### Coverage Statistics

- Total PRD FRs: 44
- FRs covered in epics: 0
- Coverage percentage: 0%

### Assessment

No epics or stories have been created for the Demo Recording feature. This is expected — the PRD was just completed. All 44 FRs will need epic coverage before implementation begins.

## UX Alignment Assessment

### UX Document Status

**Not found** — no UX document exists for the Demo Recording feature.

### UX Implied Assessment

The Demo Recording feature produces several user-facing outputs that imply UX design needs:

- HTML reports with embedded video, timelines, and reproduction steps
- Linear comment formatting for demo links
- Metrics dashboard (DevOps persona in Journey 3)
- Manual re-trigger UI in Linear

### Warnings

- **⚠️ UX design recommended before development** — HTML report templates, video player integration, and dashboard UI should be designed upfront
- The PRD describes report structure in FR11-FR17, which partially compensates for missing UX docs

### Recommendation

Create a UX design document covering:
1. HTML report layout (video embed, timeline, repro steps sections)
2. Video player controls and playback behavior
3. Fallback display for technical (asciinema) reports
4. Linear comment formatting for demo URLs
5. Metrics dashboard (if Phase 1 — DevOps basic metrics)

## Epic Quality Review

### Status

**Skipped** — no epics or stories exist for the Demo Recording feature.

### Readiness Note

Epic quality validation should be performed after epics are created, checking:
- Each epic delivers user value (not technical milestones)
- Epic independence (no forward dependencies)
- Story sizing (independently completable)
- Acceptance criteria in Given/When/Then format

## Summary and Recommendations

### Overall Readiness Status

**NEEDS WORK** — The PRD is complete and well-structured, but downstream artifacts (epics, stories, UX) do not exist yet.

### Critical Issues Requiring Immediate Action

1. **No epics or stories exist** — All 44 FRs lack implementation plans. Must create epics and stories before development can begin.
2. **Spelling: "Koncierto" → "Koncerto"** — The PRD uses the wrong project name throughout. Should be corrected.
3. **No UX document exists** — User-facing outputs (HTML reports, video player, dashboard) need UX design before implementation.
4. **Screenshots fallback not specified** — Brainstorming called for screenshots as fallback when video fails; PRD only lists asciinema.

### Recommended Next Steps

1. Fix project name: `Koncierto` → `Koncerto` in the PRD
2. Create Architecture document (bmad-bmm-create-architecture)
3. Create UX design document for HTML reports and dashboards
4. Create Epics and Stories (bmad-bmm-create-epics-and-stories)
5. Run Epic Coverage Validation after epics are created

### Assessment Summary

| Area | Finding | Severity |
|------|---------|----------|
| PRD Completeness | 44 FRs, 16 NFRs, comprehensive | ✅ Good |
| Epic Coverage | 0% — no epics exist | 🔴 Critical |
| UX Alignment | UX implied but no document | 🟠 Warning |
| Spelling Error | "Koncierto" used throughout | 🟡 Minor |
| Scope Clarity | Clear MVP/Growth/Vision phases | ✅ Good |
| Measurability | All criteria have targets | ✅ Good |
