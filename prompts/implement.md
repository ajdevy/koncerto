Implement the changes required for {{ issue.identifier }}.

Title: {{ issue.title }}
Description: {{ issue.description }}

Work in the current directory — the git branch is already set up.

## Instructions

1. Start by exploring the project structure — list the directory tree and read key files to understand the framework and conventions.
2. Make the necessary code changes to implement the issue requirements.
3. Run the project's test suite to verify your changes work.
4. Maintain 100% test coverage on new code.

**Do not commit Koncerto pipeline artifacts.** The following are local orchestration files only — never stage or commit them:
- `.koncerto/` (trace `.jsonl` logs, clarification drafts)
- `.review-*` (review status, output, attempt counters)
- `.model-exhausted*` (model retry state)

If `.gitignore` is missing these entries, Koncerto adds them automatically before auto-commit. Only commit application source, tests, migrations, and docs.

If you need clarification on requirements, write a file at `.koncerto/clarification.md` with your questions. The orchestrator will detect this file, create a comment on the Linear issue, move it to Blocked state, and assign it to the issue creator.

Your changes will be automatically committed and pushed using conventional commits format, and a pull request will be created only if you complete the implementation without requesting clarification.
