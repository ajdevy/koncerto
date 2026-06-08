# Epic 3: Workflow Engine

**Story Points:** 8  
**Priority:** P0  
**Status:** Complete  

---

## Story 3.1: FrontMatterParser

**ID:** 3.1  
**Title:** FrontMatterParser  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to parse workflow definitions from markdown files
- **So that** configuration and prompt templates are separated cleanly

### Acceptance Criteria
- [ ] Parse YAML front matter between --- delimiters
- [ ] Extract body content after front matter
- [ ] Return WorkflowDefinition with config map and prompt template
- [ ] Handle missing front matter gracefully
- [ ] Throw error for malformed YAML
- [ ] Unit tests cover all cases

### Technical Notes
- Use SnakeYAML for parsing
- Normalize line endings (\r\n → \n)
- Validate front matter is a map type
- Handle empty YAML gracefully

### Implementation
- File: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/FrontMatterParser.kt`
- Tests: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/FrontMatterParserTest.kt`

---

## Story 3.2: WorkflowLoader

**ID:** 3.2  
**Title:** WorkflowLoader  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to load workflow definitions from file paths
- **So that** workflows can be loaded from disk

### Acceptance Criteria
- [ ] Load workflow from Path
- [ ] Throw missing_workflow_file error for non-existent files
- [ ] Throw workflow_parse_error for invalid content
- [ ] Support loadInto() to populate cache
- [ ] Unit tests cover all cases

### Technical Notes
- Use FrontMatterParser for parsing
- Read file content as string
- Handle file system errors gracefully

### Implementation
- File: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/WorkflowLoader.kt`
- Tests: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/WorkflowLoaderTest.kt`

---

## Story 3.3: WorkflowCache

**ID:** 3.3  
**Title:** WorkflowCache  
**Points:** 1  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to cache the current workflow definition
- **So that** the active workflow is always accessible

### Acceptance Criteria
- [ ] AtomicReference-based thread-safe cache
- [ ] set() to store workflow definition
- [ ] current() to retrieve current workflow
- [ ] Throw error if no workflow loaded
- [ ] Unit tests cover all cases

### Technical Notes
- Use AtomicReference for thread safety
- Immutable storage pattern
- Clear error message when empty

### Implementation
- File: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/WorkflowCache.kt`
- Tests: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/WorkflowCacheTest.kt`

---

## Story 3.4: PromptRenderer

**ID:** 3.4  
**Title:** PromptRenderer  
**Points:** 3  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to render prompt templates with variable substitution
- **So that** prompts are dynamically generated with issue context

### Acceptance Criteria
- [ ] Render {{ variable }} syntax in templates
- [ ] Support nested object access ({{ issue.identifier }})
- [ ] Validate all variables exist in context
- [ ] Throw template_render_error for unresolved variables
- [ ] Handle empty templates
- [ ] Unit tests cover all cases

### Technical Notes
- Use liqp template engine
- Strict mode: fail on missing variables
- Stringify complex types (Instant, Map, List)
- Support dot notation for nested access

### Implementation
- File: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/PromptRenderer.kt`
- Tests: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/PromptRendererTest.kt`
