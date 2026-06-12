# Epic 13.06: Visual Dependency Graph

**Story Points:** 3  
**Priority:** P2  
**Status:** Complete  

---

## Story 13.06.1: Dependencies API Endpoint

**ID:** 13.06.1  
**Title:** Dependencies API Endpoint  
**Points:** 1  
**Priority:** P2  

### User Story
- **As a** developer
- **I want** a `/api/v1/dependencies` endpoint for graph data
- **So that** the frontend can render dependency relationships

### Acceptance Criteria
- [x] `GET /api/v1/dependencies` returns nodes and edges as JSON
- [x] `DependencyGraph.build()` constructs graph from orchestrator state
- [x] Nodes include id, label, type, and status properties
- [x] Edges include source, target, and relationship type
- [x] Efficient data structure for large graphs

### Technical Notes
- `DependencyGraph` class in orchestrator module
- Graph built from issue dependency data
- Nodes deduplicated by identifier
- Pagination support for large datasets

### Implementation
- File: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DependencyGraph.kt`
- References: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`

---

## Story 13.06.2: vis-network Visualization

**ID:** 13.06.2  
**Title:** vis-network Visualization  
**Points:** 1  
**Priority:** P2  

### User Story
- **As a** developer
- **I want** a frontend with vis-network visualization
- **So that** I can see issue dependencies visually

### Acceptance Criteria
- [x] vis-network library integrated in dashboard HTML
- [x] Nodes styled by type and status with color coding
- [x] Directed edges showing dependency direction
- [x] Physics simulation for automatic layout
- [x] Smooth animations for graph updates

### Technical Notes
- vis-network loaded via CDN
- Nodes colored by agent type or status
- Physics engine with barnesHut solver for layout
- Auto-resize on window resize

### Implementation
- File: `koncerto-dashboard/src/main/resources/static/dashboard.html`
- References: `vis-network CDN`

---

## Story 13.06.3: Interactive Graph Controls

**ID:** 13.06.3  
**Title:** Interactive Graph Controls  
**Points:** 1  
**Priority:** P2  

### User Story
- **As a** developer
- **I want** interactive graph with zoom, pan, and filters
- **So that** I can navigate complex dependency graphs

### Acceptance Criteria
- [x] Zoom in/out with mouse wheel and buttons
- [x] Pan via click-and-drag
- [x] Click node to show details
- [x] Filter by project, agent, and state
- [x] Real-time updates via polling

### Technical Notes
- vis-network built-in interaction controls
- Filter controls in dashboard sidebar
- Click handler shows issue details in side panel
- 5-second polling interval for real-time updates

### Implementation
- File: `koncerto-dashboard/src/main/resources/static/dashboard.html`
- References: `vis-network interaction API`
