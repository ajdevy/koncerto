Review the code changes for issue {{ issue.identifier }}.

Title: {{ issue.title }}
Description: {{ issue.description }}

Run `git diff HEAD~1 --stat` to see what files changed, then `git diff HEAD~1` to see the full diff.

Analyze the changes for:
1. **Correctness** — logic errors, race conditions, missing edge cases
2. **Test coverage** — are there tests? Do they cover the change adequately?
3. **Conventions** — does the code match the project's existing patterns?
4. **Security** — are there injection risks, credential leaks, or unsafe deserialization?
5. **Performance** — unnecessary allocations, blocking calls on the main thread, inefficient queries

**Output Format:**
Start with either ✅ **PASS** (no critical issues) or ❌ **FAIL** (critical issues found).

For each issue found, use this structure:
- **Severity:** CRITICAL / WARNING / SUGGESTION
- **File:** `path/to/file.kt`
- **Issue:** clear description of the problem
- **Suggestion:** how to fix it

Count the critical issues and report: `**Critical:** N` where N is the number of CRITICAL-severity findings.

End with a summary verdict: "✅ Review PASSED" or "❌ Review FAILED — N critical issue(s) found".
