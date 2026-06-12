# Config Editor UI — Design Spec

**Date:** 2026-06-12
**Status:** Design

## Overview

Web-based YAML editor for workflow configuration, integrated into the dashboard. Uses CodeMirror 6 for syntax highlighting with a REST API backend for load/save/validate operations against the server-side configuration.

## Motivation

Workflow configuration (`WORKFLOW.md` YAML front matter) is currently edited via the filesystem. Operators need a browser-based editor that validates configuration before applying it, tracks changes, and provides schema-aware autocomplete. This reduces configuration errors and provides an auditable change trail.

## Technical Design

### API

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/v1/config` | Return current raw YAML config |
| `PUT` | `/api/v1/config` | Save validated config (reloads orchestrator) |
| `GET` | `/api/v1/config/schema` | Return JSON Schema for autocomplete |

### Backend

`ConfigService` with three operations:

- `load()` — read YAML from configured path, return as string
- `save(yaml: String)` — parse, validate, write to disk, trigger orchestrator reload
- `schema()` — introspect `ServiceConfig` + `ProjectConfig` data classes → JSON Schema

### Frontend

Vanilla JS dashboard page with CodeMirror 6:
- YAML syntax highlighting and linting
- Save button triggers `PUT /api/v1/config`
- Success notification on save, error display on validation failure
- Read-only mode for non-admin users

## Configuration

```yaml
koncerto:
  config-editor:
    enabled: true
    max-file-size: 1MB
    schema-cache-ttl: 300s
```

## Testing Strategy

- `ConfigServiceTest` — load/save/validate round-trip with valid and invalid YAML
- `ConfigControllerTest` — HTTP 200 on valid PUT, 400 on invalid YAML
- Frontend: manual testing of editor save/load flow
- Schema generation test: verify JSON Schema covers all config fields

## Open Questions

- Should save trigger an immediate orchestrator restart, or a rolling reload?
- Do we need config version history in v1, or is git-based history sufficient?
