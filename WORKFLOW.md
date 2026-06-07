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
  max_concurrent_agents: 2
  max_turns: 5
codex:
  command: codex app-server
  turn_timeout_ms: 3600000
  stall_timeout_ms: 300000
---

You are working on Linear issue {{ issue.identifier }}.

Title: {{ issue.title }}
Body: {{ issue.description }}
