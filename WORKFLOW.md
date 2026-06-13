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
      max_concurrent_agents: 2
      max_turns: 5
      max_review_attempts: 3
      stages:
        Todo:
          prompt: prompts/implement.md
          on_complete_state: "In Review"
        "In Review":
          prompt: prompts/review.md
          agent_kind: claude
          command: claude --print
          on_complete_state: "Done"
          on_failure_state: "Needs Fix"
          max_review_attempts: 3
        "Needs Fix":
          prompt: prompts/fix-review.md
          agent_kind: opencode
          on_complete_state: "In Review"
---

You are working on Linear issue {{ issue.identifier }}.

Title: {{ issue.title }}
Body: {{ issue.description }}
