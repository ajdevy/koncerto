# BMAD Output Artifacts

This directory contains all planning and implementation artifacts generated using the BMAD (Breakthrough Method for Agile AI-Driven Development) methodology.

## Directory Structure

```
_bmad-output/
├── planning-artifacts/          # Phase 1-3: Analysis, Planning, Solutioning
│   ├── project-brief.md         # Analyst output: Project vision and goals
│   ├── prd.md                   # PM output: Product Requirements Document
│   └── architecture.md          # Architect output: Technical architecture
│
└── implementation-artifacts/    # Phase 4: Implementation
    └── stories/                 # Developer output: User stories
        ├── epic-1-core-foundation.md
        ├── epic-2-structured-logging.md
        ├── epic-3-workflow-engine.md
        ├── epic-4-workspace-management.md
        ├── epic-5-linear-integration.md
        ├── epic-6-agent-runtime.md
        ├── epic-7-orchestration.md
        ├── epic-8-dashboard-api.md
        └── epic-9-application-assembly.md
```

## BMAD Workflow

### Phase 1: Analysis
- **Agent:** Alex the Analyst
- **Output:** Project Brief
- **Purpose:** Understand problem space, define vision

### Phase 2: Planning
- **Agent:** Sarah the PM
- **Output:** PRD with Epics & Stories
- **Purpose:** Define requirements, break down features

### Phase 3: Solutioning
- **Agent:** Fred the Architect
- **Output:** Architecture Document
- **Purpose:** Design technical solution

### Phase 4: Implementation
- **Agent:** Dev the Developer
- **Output:** User Story Files
- **Purpose:** Implement features story by story

## Document Standards

### Project Brief
- Executive summary
- Problem statement
- Target users
- Success metrics
- Scope (in/out)

### PRD
- Functional requirements (FR-XX)
- Non-functional requirements (NFR-XX)
- Epics with story points
- User stories with acceptance criteria

### Architecture
- System overview
- Technology stack
- Module architecture
- Data flows
- Error handling
- Testing strategy

### Story Files
- User story format (As a... I want... So that...)
- Acceptance criteria (checkboxes)
- Technical notes
- Implementation file references

## Usage

1. **Start New Project:** Run Analyst agent to create Project Brief
2. **Define Requirements:** Run PM agent to create PRD
3. **Design Solution:** Run Architect agent to create Architecture
4. **Implement:** Run Developer agent for each story

## BMAD Commands

| Command | Agent | Task |
|---------|-------|------|
| /analyst | Analyst | Brainstorming, Project Brief |
| /pm | PM | PRD Creation |
| /architect | Architect | Architecture Design |
| /developer | Developer | Story Implementation |
