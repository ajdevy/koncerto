Fix the code review findings for {{ issue.identifier }} — "{{ issue.title }}".

A code review was performed and found issues that need to be fixed. The review report is in `_bmad-output/review-reports/` (look for the file matching this issue identifier). Read it to understand what needs to change.

## Instructions

1. Read the review report from `_bmad-output/review-reports/` to find the **Critical Findings** and **Warnings**
2. Fix each critical finding in order — edit the source files to address each issue
3. After fixing all critical issues, run `./gradlew build` to verify compilation
4. Run `./gradlew test` to verify tests still pass
5. Commit changes with a commit message like: `fix: address review findings for {{ issue.identifier }}`

## Format for Each Fix

For each critical finding:
- Locate the file and line referenced
- Apply the recommended fix (or a better fix if you have one)
- Verify the fix doesn't break other code

## Error Handling

If the review report can't be found, check if `_bmad-output/review-reports/` exists and list its contents.

## Important Notes

- This is a fix-only pass — do not add new features or change behavior beyond addressing the review findings
- If a finding is a false positive, leave a comment explaining why and skip it
- Do not modify files that aren't related to the review findings
