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
  remote_url: $GIT_REMOTE_URL
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
      blocked_state: "Blocked"  # Invariant: every automated transition to this state posts a comment first (see architecture/issue-lifecycle-state-machine.md)
      project_admin: "user-1"
    reviewer:
      kind: human
    git_remote_url: $GIT_REMOTE_URL
    workspace:
      root: $KONCERTO_WORKSPACE_ROOT
    agent:
      kind: opencode
      max_concurrent_agents: 4
      docker:
        enabled: false
      max_turns: 5
      max_review_attempts: 3
      limit_pause:
        enabled: true
        linear_comments: true
        claude_default_resume_ms: 18000000
        codex_default_resume_ms: 18000000
      stages:
        Todo:
          prompt: prompts/implement.md
          agent_kind: codex
          command: codex exec --json --skip-git-repo-check -s danger-full-access
          effort: medium
          on_complete_state: "In Review"
        "In Progress":
          prompt: Agent is working on this issue. Check the workspace for progress and write PASS if work is actively progressing.
          agent_kind: claude
          command: claude --print
          model: claude-sonnet-4-6
          on_complete_state: "In Review"
        "In Review":
          prompt: prompts/review.md
          agent_kind: claude
          command: claude --print
          model: claude-sonnet-4-6
          effort: medium
          on_complete_state: "Ready for Human Review"
          max_review_attempts: 3
        "Ready for Human Review":
          prompt: prompts/human-review.md
          agent_kind: human
          on_complete_state: "Done"
# Deploys the target project in a Docker container after review passes
# so the demo recorder can capture the running app.
target_project_deploy:
  enabled: true
  trigger: review_passed
  health_check_timeout_sec: 60
  port_range: "32768-33000"

demo_recording:
  enabled: true
  trigger: review_passed
  target_url: $DEMO_TARGET_URL
  platform:
    web: playwright
    terminal: asciinema
  quality:
    resolution: 1280x720
    fps: 10
    codec: vp9
  storage:
    r2_endpoint: $R2_ENDPOINT
    r2_bucket: $R2_BUCKET
    r2_access_key: $R2_ACCESS_KEY
    r2_secret_key: $R2_SECRET_KEY
    public_url_base: $R2_PUBLIC_URL_BASE
    presigned_url_ttl: 604800  # 7 days (R2 max); for permanent URLs, set R2_PUBLIC_URL_BASE to R2.dev or custom domain
    region: auto
  ai:
    model: free
    timeline: false
    repro_steps: false
  retry:
    max_attempts: 3
    backoff: exponential
  error:
    on_failure: mark_blocked
  cleanup_interval_hours: 24

---

You are working on Linear issue {{ issue.identifier }}.

Title: {{ issue.title }}
Body: {{ issue.description }}
