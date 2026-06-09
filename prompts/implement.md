Implement the changes required for {{ issue.identifier }}.

Title: {{ issue.title }}
Description: {{ issue.description }}

Work in the current directory — the git branch is already set up.

Requirements:
1. Write tests first, then implementation (Red–Green–Refactor)
2. Run the relevant tests after each change to verify
3. Follow existing project conventions (code style, patterns, architecture)
4. Maintain 100% test coverage on new code

If you need clarification on requirements, write a file at `.koncerto/clarification.md` with your questions. The orchestrator will detect this file, create a comment on the Linear issue, move it to Blocked state, and assign it to the issue creator.

Your changes will be automatically committed and pushed, and a pull request will be created only if you complete the implementation without requesting clarification.
