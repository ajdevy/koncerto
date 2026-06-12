# Epic 13.05: Config Editor UI

**Story Points:** 5  
**Priority:** P2  
**Status:** Complete  

---

## Story 13.05.1: Config REST API

**ID:** 13.05.1  
**Title:** Config REST API  
**Points:** 2  
**Priority:** P2  

### User Story
- **As an** operator
- **I want** REST endpoints for configuration management
- **So that** I can read, write, and validate workflow configs programmatically

### Acceptance Criteria
- [x] `GET /api/v1/config` returns current configuration
- [x] `PUT /api/v1/config` accepts and saves new configuration
- [x] `GET /api/v1/config/schema` returns configuration schema
- [x] ConfigService with load, save, and validate methods
- [x] JSON responses with proper content types

### Technical Notes
- `ConfigService` in app module for centralized config management
- Schema endpoint auto-generates from configuration model
- Validation returns detailed error messages with line numbers
- Save creates a backup of previous config

### Implementation
- File: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`
- File: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/ConfigService.kt`

---

## Story 13.05.2: CodeMirror Editor Integration

**ID:** 13.05.2  
**Title:** CodeMirror Editor Integration  
**Points:** 2  
**Priority:** P2  

### User Story
- **As an** operator
- **I want** a web-based YAML editor in the dashboard
- **So that** I can edit workflow configuration through the UI

### Acceptance Criteria
- [x] CodeMirror 6 editor embedded in dashboard HTML
- [x] YAML syntax highlighting
- [x] Line numbers and bracket matching
- [x] Save and load buttons with feedback
- [x] Responsive layout for different screen sizes

### Technical Notes
- CodeMirror 6 loaded via CDN for simplicity
- YAML language mode for syntax highlighting
- Editor theme matches dashboard dark theme
- Keyboard shortcuts for save (Ctrl+S)

### Implementation
- File: `koncerto-dashboard/src/main/resources/static/dashboard.html`
- References: `CodeMirror 6 CDN`, `@codemirror/lang-yaml`

---

## Story 13.05.3: YAML Validation

**ID:** 13.05.3  
**Title:** YAML Validation  
**Points:** 1  
**Priority:** P2  

### User Story
- **As an** operator
- **I want** YAML validation with human-readable error messages
- **So that** I can quickly fix configuration issues

### Acceptance Criteria
- [x] Client-side YAML parsing validation in editor
- [x] Server-side validation on save via `/api/v1/config`
- [x] Human-readable error messages with line and column references
- [x] Inline error highlighting in CodeMirror editor
- [x] Config history foundation with version tracking

### Technical Notes
- Client-side uses `js-yaml` for real-time validation
- Server-side validates against configuration schema
- Errors mapped to specific line numbers for inline display
- Config history stored with timestamps for audit trail

### Implementation
- File: `koncerto-dashboard/src/main/resources/static/dashboard.html`
- File: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/ConfigService.kt`
