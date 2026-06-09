Review the pull request for {{ issue.identifier }}.

Title: {{ issue.title }}
Description: {{ issue.description }}

The implementing agent has pushed a branch and created a PR. Your job is to review the changes and post review comments on the PR using the `gh` CLI.

Use the following commands:
- `gh pr view --json number,title,body,headRefName,baseRefName` — find the PR for branch `feature/{{ issue.identifier }}`
- `gh pr diff {{ issue.identifier }}` — view the code changes
- `gh pr review {{ issue.identifier }} --comment --body '...'` — post a review with inline feedback
- `gh pr review {{ issue.identifier }} --approve --body '...'` — approve if changes look good

Focus on:
1. Code correctness and edge cases
2. Test coverage
3. Following project conventions
4. Security and performance

When you find issues, post specific line-level comments using `gh pr review` with the `--comment` flag and reference the relevant code.
