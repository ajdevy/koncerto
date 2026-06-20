---
poll_interval_ms: 30000
max_retry_backoff_ms: 300000
hooks:
  timeout_ms: 60000
git:
  enabled: true
  branch_prefix: "feature/"
  auto_commit: true
  auto_push: false
  create_pr: false
  pr_base: main
projects:
  promomesh:
    tracker:
      kind: linear
      api_key: $LINEAR_API_KEY
      project_slug: deb41f3a3628
      active_states:
        - Todo
        - "In Progress"
        - "In Review"
      terminal_states:
        - Done
      blocked_state: "Blocked"
      project_admin: "user-1"
    workspace:
      root: $KONCERTO_WORKSPACE_ROOT
    agent:
      kind: opencode
      max_concurrent_agents: 4
      docker:
        enabled: false
      max_turns: 5
      max_review_attempts: 3
      stages:
        Todo:
          prompt: prompts/implement.md
          agent_kind: codex
          command: codex exec --json --skip-git-repo-check
          effort: medium
          on_complete_state: "In Review"
        "In Review":
          prompt: prompts/review.md
          agent_kind: claude
          command: claude --print
          model: claude-sonnet-4-6
          effort: medium
          on_complete_state: "Done"
          max_review_attempts: 3
  default:
    tracker:
      kind: linear
      api_key: $LINEAR_API_KEY
      project_slug: d3c2ba0a66a6
      active_states:
        - Todo
        - "In Progress"
        - "In Review"
      terminal_states:
        - Done
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
      docker:
        enabled: false
      max_turns: 5
      max_review_attempts: 3
      stages:
        Todo:
          prompt: prompts/implement.md
          agent_kind: codex
          command: codex exec --json --skip-git-repo-check
          effort: medium
          on_complete_state: "In Review"
        "In Review":
          prompt: prompts/review.md
          agent_kind: claude
          command: claude --print
          model: claude-sonnet-4-6
          effort: medium
          on_complete_state: "Done"
          max_review_attempts: 3
---

You are working on Linear issue {{ issue.identifier }}.

Title: {{ issue.title }}
Body: {{ issue.description }}
