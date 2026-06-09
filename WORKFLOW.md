---
tracker:
  kind: linear
  api_key: $LINEAR_API_KEY
  project_slug: EXAMPLE
  active_states:
    - Todo
  terminal_states:
    - Done
    - Cancelled
polling:
  interval_ms: 30000
workspace:
  root: $KONCERTO_WORKSPACE_ROOT
hooks:
  timeout_ms: 60000
agent:
  kind: opencode
  max_concurrent_agents: 2
  max_turns: 5
  blocked_state: "Blocked"
  project_admin: "user-1"
  stages:
    Todo:
      prompt: prompts/implement.md
      on_complete_state: "In Review"
    "In Review":
      prompt: prompts/review.md
      on_complete_state: "Done"
codex:
  command: codex app-server
  turn_timeout_ms: 3600000
  stall_timeout_ms: 300000
git:
  enabled: true
  branch_prefix: "feature/"
  auto_commit: true
  auto_push: true
  create_pr: true
  pr_base: main
---

You are working on Linear issue {{ issue.identifier }}.

Title: {{ issue.title }}
Body: {{ issue.description }}
