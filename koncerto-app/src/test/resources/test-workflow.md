---
poll_interval_ms: 30000
hooks:
  timeout_ms: 60000
projects:
  test:
    tracker:
      kind: linear
      api_key: test-key-123
      project_slug: TEST
      active_states:
        - Todo
      terminal_states:
        - Done
        - Cancelled
    workspace:
      root: /tmp/koncerto_test_workspaces
    agent:
      kind: opencode
      max_concurrent_agents: 1
      max_turns: 2
---
