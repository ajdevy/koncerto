# BMAD Configuration

## Project: Koncerto

**Description:** Kotlin/Spring Boot orchestration service for AI coding agents  
**Type:** Backend Service  
**Tech Stack:** Kotlin, Spring Boot, Gradle, Coroutines, Playwright, Docker, SQLite  
**Version:** 2.0  

## Agents

| Agent | Role | Status |
|-------|------|--------|
| Analyst | Product discovery, research | Active |
| PM | Requirements, PRD | Active |
| Architect | Technical design | Active |
| Developer | Implementation | Active |
| UX Designer | Interface design | N/A (CLI tool) |
| Technical Writer | Documentation | Active |
| QA | Testing, validation | Active |
| Scrum Master | Sprint tracking | Active |

## Workflow

1. **Analysis** → Analyst creates Project Brief
2. **Planning** → PM creates PRD with Epics/Stories
3. **Solutioning** → Architect creates Architecture Doc
4. **Implementation** → Developer implements Stories
5. **Review** → Auto-review (Claude) + QA validation
6. **Demo** → Playwright recording + R2 upload
7. **Deploy** → Target project Docker build/run

## Document Standards

- All documents use Markdown
- PRD follows BMAD template
- Architecture follows BMAD template
- Stories use standard format with acceptance criteria
- Bug-fix history in AGENTS.md

## Implementation Status

| Epic | Status |
|------|--------|
| Epic 1-9: Core, Logging, Workflow, Workspace, Linear, Agent, Orchestration, Dashboard, Assembly | **Complete** |
| Epic 10: Blocker Tracking & Parallel Groups | **Complete** |
| Epic 11: Agent Specialization Routing | **Complete** |
| Epic 12: Workflow Chaining | **Complete** |
| Epic 13: Beyond-Scope Features | **Mostly Complete** |
| Epic 14: Scalable Multi-Project | **Partially Complete** |
| Epic 15: Cloud-Native Deploy | **Not Started** |
| Epic 16: Claude Code Reviews | **Complete** |
| Epic 17: ngrok Tunnel | **Complete** |
| Demo Recording Pipeline | **Complete** |
| Target Project Deploy | **Complete** |
| Notifications (Webhook/Telegram/Email) | **Complete** |
| Metrics (SQLite + Prometheus) | **Complete** |
| Epic 18–23: Review Quality (telemetry, routing, context, gate, calibration, multi-agent) | **Planned** — see `prd-review-quality.md` |
