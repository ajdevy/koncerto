# Story Implementation Task

**Purpose:** Implement a specific user story with tests and documentation

## When to Use
- During sprint implementation
- When developing features
- For bug fixes
- For technical improvements

## Process

1. **Understand Story**
   - Review acceptance criteria
   - Understand dependencies
   - Clarify requirements

2. **Plan Implementation**
   - Design approach
   - Identify files to modify
   - Plan test strategy
   - Estimate effort

3. **Implement**
   - Write code following standards
   - Implement tests
   - Update documentation
   - Follow coding conventions

4. **Test**
   - Run unit tests
   - Run integration tests
   - Verify acceptance criteria
   - Check edge cases

5. **Review**
   - Self-review code
   - Create pull request
   - Address review feedback
   - Merge when approved

## Git Hygiene (Koncerto target projects)

Never commit orchestrator pipeline artifacts. These files are written locally during dispatch/review and must stay out of PRs:

| Pattern | Contents |
|---------|----------|
| `.koncerto/` | Trace `.jsonl` logs (`dispatch-trace-*`, `review-trace-*`, `deploy-trace-*`), clarification drafts |
| `.review-*` | Review status, output, attempt counter, PR comment body |
| `.model-exhausted*` | Free-model retry exhaustion state |

Koncerto auto-appends these patterns to the target project's `.gitignore` and untracks them before `git commit`. If you see them in a PR diff, remove with `git rm --cached` and add the gitignore block.

## Output Format

```markdown
# Story Implementation: [Story Title]

## Story Details
- **ID:** [Story ID]
- **Title:** [Title]
- **Acceptance Criteria:** [Criteria]

## Implementation Plan
1. [Task 1]
2. [Task 2]
3. [Task 3]

## Files Modified
- [File 1]: [Changes]
- [File 2]: [Changes]

## Tests Added
- [Test 1]: [Description]
- [Test 2]: [Description]

## Technical Decisions
- [Decision 1]: [Rationale]
- [Decision 2]: [Rationale]

## Testing Results
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Acceptance criteria met
- [ ] Code review complete

## Pull Request
- **Title:** [PR Title]
- **Description:** [PR Description]
- **Link:** [PR URL]
```
