---
title: 'Configure Git Remote URL for All Projects'
slug: 'configure-git-remote-url'
created: '2026-06-20'
status: 'verified'
stepsCompleted: [1, 2, 3, 4]
tech_stack: ['Kotlin', 'Spring Boot', 'Git', 'GitHub CLI']
files_to_modify:
  - 'koncerto-core/src/main/kotlin/.../config/ServiceConfig.kt'
  - 'koncerto-workspace/src/main/kotlin/.../workspace/GitWorkflow.kt'
  - 'WORKFLOW.md'
  - 'docker-compose.yml'
code_patterns: []
test_patterns: []
---

# Tech-Spec: Configure Git Remote URL for All Projects

**Created:** 2026-06-20

## Overview

### Problem Statement

Workspace repos are created via `git init` with no origin remote configured. `GitWorkflow.commitAndPush()` runs `git push -u origin <branch>` and `createPullRequest()` runs `gh pr create`, but both fail because:
- No origin remote exists in the workspace repo
- `GH_TOKEN` is not passed to the container for `gh` auth
- `auto_push` and `create_pr` are set to `false` in WORKFLOW.md

### Solution

1. Add `remoteUrl` field to `GitConfig` data class and wire it through `parseGitConfig()`
2. In `GitWorkflow.createBranch()`, after `git init`, add origin remote (with GH_TOKEN embedded for auth)
3. Update WORKFLOW.md with `remote_url` for both projects (`promomesh` + `default`) and flip `auto_push`/`create_pr` to `true`
4. Pass `GH_TOKEN` from host env through docker-compose.yml

### Scope

**In Scope:**
- GitConfig data class — add `remoteUrl` field
- parseGitConfig() — parse `remote_url` from YAML
- GitWorkflow.createBranch() — set up origin remote after git init
- WORKFLOW.md — add `remote_url` for both projects, enable `auto_push`/`create_pr`
- docker-compose.yml — add `GH_TOKEN` env var

**Out of Scope:**
- GitHub login flow in the dashboard
- SSH key setup
- Sub-branch or subtask remote configuration

## Context for Development

### Codebase Patterns

- Convention: Gradle multi-module Kotlin project with `ServiceConfig.kt` parsing YAML configs via hand-written `parse*Config()` functions
- GitConfig is immutable data class with defaults constructed in `parseGitConfig()` from a `Map<*, *>?`
- GitWorkflow.createBranch() uses `runGitSafe()` helper to execute git commands
- auth for git push uses embedded token in remote URL (not SSH or credential helper)
- The `gh` CLI uses `GH_TOKEN` env var for auth

### Files to Reference

| File | Purpose |
| ---- | ------- |
| `koncerto-core/.../config/ServiceConfig.kt:411-418` | GitConfig data class definition |
| `koncerto-core/.../config/ServiceConfig.kt:318-328` | parseGitConfig() YAML parser |
| `koncerto-workspace/.../workspace/GitWorkflow.kt:55-79` | createBranch() — needs origin remote setup |
| `koncerto-workspace/.../workspace/GitWorkflow.kt:81-93` | commitAndPush() — uses origin remote |
| `WORKFLOW.md:6-12` | Git config section in YAML |
| `docker-compose.yml:8-16` | Environment variables for the container |

### Technical Decisions

- **Auth approach**: Embed GH_TOKEN in the remote URL (`https://x-access-token:<token>@github.com/...`) — avoids credential helpers or SSH setup in Alpine container
- **Remote URL from config**: Each project in WORKFLOW.md gets its own `remote_url` since they target different repos
- **Fallback**: If no `remote_url` is configured, skip remote setup (backward compat)
- **GH_TOKEN**: Read from env var, not a file — simplest path for container deployment

## Implementation Plan

### Tasks

- [ ] Task 1: Add `remoteUrl` field to `GitConfig` data class and parse it from YAML
- [ ] Task 2: Set up origin remote in `GitWorkflow.createBranch()` with GH_TOKEN auth
- [ ] Task 3: Update WORKFLOW.md — add `remote_url` for both projects, enable `auto_push`/`create_pr`
- [ ] Task 4: Pass `GH_TOKEN` through docker-compose.yml
- [ ] Task 5: Rebuild, restart, and verify

### Acceptance Criteria

- [ ] AC 1: Given a project with `remote_url` configured in WORKFLOW.md, when `createBranch()` runs, then the workspace repo has an origin remote pointing to the configured URL
- [ ] AC 2: Given `GH_TOKEN` is set, when origin remote is added, then the token is embedded in the URL for authenticated push
- [ ] AC 3: Given `auto_push: true`, when `commitAndPush()` runs, then code is pushed to the origin remote
- [ ] AC 4: Given `create_pr: true`, when `createPullRequest()` runs, then a PR is created on GitHub
- [ ] AC 5: Given `remote_url` is not configured, when `createBranch()` runs, then no origin remote is added (backward compatible)
- [ ] AC 6: Given both projects are configured, when running the workflow, then each project's workspace has the correct remote

## Additional Context

### Dependencies

- GitHub CLI (`gh`) must be installed in the container (already in Dockerfile)
- `GH_TOKEN` must be set in the host environment before `docker compose up`

### Testing Strategy

- Unit test: GitConfig serialization with remoteUrl field
- Unit test: GitWorkflow remote setup with/without GH_TOKEN
- Manual: Deploy, trigger a story, verify push + PR creation

### Notes

- The `default` project doesn't have an explicit remote repo URL yet — need to ask user or use a placeholder
- promomesh remote: `https://github.com/ajdevy/promomesh.git`
