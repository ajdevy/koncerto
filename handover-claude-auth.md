# Handover: Claude Auth Fix

## Problem
Claude OAuth login fails in Docker. The `claude auth login` process starts a local callback server on `127.0.0.1:PORT` inside the container, but the OAuth redirect cannot reach localhost inside Docker from the external browser.

## Current State
- `claude auth login` process (PID 75432) is still running in container `koncerto-koncerto-app-1`
- Local callback server is listening on port 45023 inside the container
- OAuth code was obtained from browser but the callback can't complete because `platform.claude.com` cannot redirect to `127.0.0.1:45023` inside Docker
- `/root/.claude.json` exists but has no OAuth tokens — only metadata and project configs
- Auth check in `AgentAuthChecker.kt` calls `claude auth status --json` which returns "not authenticated"

## What Was Tried
1. **Login via URL** — generated URL, user authorized in browser — but the OAuth redirect goes to platform.claude.com, which needs to redirect back to a localhost inside Docker. That's the core issue.
2. **Manual callback injection** — tried `curl` and `python3` to POST/GET the callback URL to the local server inside the container — but curl is not installed in the container, and python3 succeeded but didn't result in tokens being saved.
3. **API-based code submission** — `/api/v1/claude-login-code` endpoint writes the code to the `claude auth login` process's stdin, but the process expects the callback via HTTP, not stdin.

## Proposed Solution
Switch from OAuth to API key authentication:

1. Add `CLAUDE_API_KEY` env var to `docker-compose.yml`
2. Add `--api-key` flag to all `claude` invocations in `AgentAuthChecker.kt`, `ClaudeReviewRuntime.kt`, and `AgentRunner.kt`
3. Alternatively: set `export ANTHROPIC_API_KEY=sk-ant-...` and let claude pick it up automatically

This bypasses the OAuth flow entirely and works reliably in Docker.

## Files to Modify
- `/Users/aj/workspace_ai/koncerto/docker-compose.yml` — add `CLAUDE_API_KEY` or `ANTHROPIC_API_KEY` env var
- `/Users/aj/workspace_ai/koncerto/koncerto-agent/src/main/kotlin/koncerto/agent/auth/AgentAuthChecker.kt` — add `--api-key` flag to `claude auth status` commands
- `/Users/aj/workspace_ai/koncerto/koncerto-agent/src/main/kotlin/koncerto/agent/review/ClaudeReviewRuntime.kt` — add `--api-key` flag to `claude --print` command
- Possibly: `/Users/aj/workspace_ai/koncerto/koncerto-dashboard/src/main/kotlin/koncerto/dashboard/ApiV1Controller.kt` — remove or fix the login endpoints

## Quick Test
```bash
# Verify claude is working with API key
docker exec koncerto-koncerto-app-1 sh -c "CLAUDE_API_KEY=sk-ant-... claude auth status --json"
# Verify review works
docker exec koncerto-koncerto-app-1 sh -c "CLAUDE_API_KEY=sk-ant-... claude --print < review_prompt.txt"
```

## Key Contacts / Context
- PromoMesh workspace at `/workspace/FLE-51` (mounted from `~/workspace_ai/promomesh`)
- Koncerto runs in Docker: `docker compose up -d --build` to restart
- Claude OAuth data would live in `/root/.claude.json` but is not persisted across restarts
- Codex uses a different auth mechanism (ChatGPT OAuth, already working)
