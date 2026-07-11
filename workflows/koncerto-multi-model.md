---
poll_interval_ms: 30000
max_retry_backoff_ms: 300000
hooks:
  timeout_ms: 60000
git:
  enabled: true
  branch_prefix: "feature/"
  auto_commit: true
  auto_push: true
  create_pr: true
  pr_base: main
projects:
  default:
    tracker:
      kind: linear
      api_key: $LINEAR_API_KEY
      project_slug: EXAMPLE
      active_states:
        - Todo
        - "In Progress"
        - "In Review"
        - "Needs Fix"
      terminal_states:
        - Done
        - Cancelled
      blocked_state: "Blocked"
      project_admin: "user-1"
    workspace:
      root: $KONCERTO_WORKSPACE_ROOT
    notifications:
      on_completed: true
      on_failed: true
      on_stalled: true
      on_clarification: true
      on_limit:
        - telegram
        - logging
      telegram:
        bot_token: $TELEGRAM_BOT_TOKEN
        chat_id: "5658965359"
    agent:
      kind: opencode
      max_concurrent_agents: 3
      max_turns: 10
      max_review_attempts: 3
      stages:
        coding:
          prompt: prompts/implement.md
          agent_kind: "codex"
          model: "codex-5.6-luna"
          max_concurrent: 2
          on_complete_state: "In Review"
        review:
          prompt: prompts/review.md
          agent_kind: "claude"
          model: "claude-opus-4-8"
          max_concurrent: 1
          on_complete_state: "Done"
          on_failure_state: "Needs Fix"
          max_review_attempts: 3
        simple:
          prompt: prompts/simple.md
          agent_kind: "opencode"
          model: "free"
          max_concurrent: 3
          on_complete_state: "In Review"
      routing_rules:
        - if_label_prefix: "feat/"
          use_agent: "codex"
          priority: 100
        - if_label: "review"
          use_agent: "claude"
          priority: 90
        - if_label_prefix: "bug/"
          use_agent: "codex"
          priority: 80
        - if_state: "Todo"
          use_agent: "opencode-free"
          priority: 50
      agents:
        codex:
          kind: "codex"
          command: "codex --model 5.6-luna"
          model: "codex-5.6-luna"
          max_concurrent: 2
        claude:
          kind: "claude"
          command: "claude --model opus-4.8 --print"
          model: "claude-opus-4-8"
          max_concurrent: 1
        opencode-free:
          kind: "opencode"
          command: "opencode"
          model: "free"
          max_concurrent: 3
---
# Multi-Model Koncerto Workflow

You are working on Linear issue {{ issue.identifier }}.

Title: {{ issue.title }}
Body: {{ issue.description }}

## Agent Routing Rules

| Condition | Agent | Model |
|-----------|-------|-------|
| Label starts with `feat/` | codex | codex-5.6-luna |
| Label is `review` | claude | claude-opus-4-8 |
| Label starts with `bug/` | codex | codex-5.6-luna |
| State is `Todo` | opencode-free | free (cycles) |

## Stages

- **coding**: Codex 5.6 Luna implements the feature → moves to "In Review"
- **review**: Claude Opus 4.8 reviews code → "Done" on pass, "Needs Fix" on fail
- **simple**: OpenCode free tier handles simple tasks → "In Review"

## Free Model Cycling

OpenCode free tier automatically cycles through built-in free models:
- Max 3 retries per model with exponential backoff (1s, 2s, 4s)
- On exhaustion: ticket → Blocked, `.model-exhausted` status file, notification sent
