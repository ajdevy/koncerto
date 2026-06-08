# Epic 4: Workspace Management

**Story Points:** 8  
**Priority:** P0  
**Status:** Complete  

---

## Story 4.1: WorkspaceKey

**ID:** 4.1  
**Title:** WorkspaceKey  
**Points:** 1  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to sanitize workspace identifiers
- **So that** workspace names are filesystem-safe

### Acceptance Criteria
- [ ] Replace unsafe characters with underscores
- [ ] Preserve alphanumeric, dots, hyphens, underscores
- [ ] Return "_" for empty strings
- [ ] Unit tests cover all cases

### Technical Notes
- Use regex pattern for safe characters
- Character-by-character replacement
- Handle edge cases (empty, all unsafe)

### Implementation
- File: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/WorkspaceKey.kt`
- Tests: `koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/WorkspaceKeyTest.kt`

---

## Story 4.2: HookExecutor

**ID:** 4.2  
**Title:** HookExecutor  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to execute shell hooks in workspaces
- **So that** custom scripts run at lifecycle events

### Acceptance Criteria
- [ ] Run bash -lc commands in workspace directory
- [ ] Timeout support via withTimeout
- [ ] Throw HookExecutionException on non-zero exit
- [ ] Log timeout warnings
- [ ] Capture stdout/stderr output
- [ ] Unit tests cover all cases

### Technical Notes
- Use ProcessBuilder for execution
- Redirect stderr to stdout
- Cap output at 2000 chars on error
- Configurable timeout via constructor

### Implementation
- File: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/HookExecutor.kt`
- Tests: `koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/ShellHookExecutorTest.kt`

---

## Story 4.3: WorkspaceManager

**ID:** 4.3  
**Title:** WorkspaceManager  
**Points:** 5  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to manage workspace lifecycle
- **So that** isolated directories are created and cleaned up properly

### Acceptance Criteria
- [ ] ensureWorkspace() creates directory if missing
- [ ] Workspace data class with path, key, createdNow
- [ ] assertInsideRoot() prevents path traversal
- [ ] removeWorkspace() deletes directory recursively
- [ ] Run lifecycle hooks (afterCreate, beforeRun, afterRun, beforeRemove)
- [ ] Graceful error handling for hook failures
- [ ] Unit tests cover all cases

### Technical Notes
- Resolve paths against absolute root
- Use Files.createDirectories for creation
- Walk and delete for removal
- Log warnings for failed hooks

### Implementation
- File: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/WorkspaceManager.kt`
- Tests: `koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/WorkspaceManagerTest.kt`
