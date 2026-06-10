# Epic 11: Agent Specialization Routing

**Story Points:** 8  
**Priority:** P1  
**Status:** Complete

---

## Story 11.1: Routing Rule Config Model

**ID:** 11.1  
**Title:** Routing Rule Config Model  
**Points:** 2  
**Priority:** P1

### User Story
- **As an** operator
- **I want** to configure routing rules that map issues to specific agents
- **So that** different issue types use appropriate agent configs

### Acceptance Criteria
- [x] RoutingRule data class with ifLabel, ifLabelPrefix, ifState, ifPriority, ifPriorityMax, useAgent, priority fields
- [x] routingRules field added to AgentProjectConfig
- [x] parseRoutingRules() in ServiceConfig reads from YAML
- [x] Rules sorted by priority descending at parse time
- [x] Validation: useAgent must reference a key in agents map
- [x] Unit tests for parsing: empty rules, single rule, multiple rules, missing useAgent

### Technical Notes
- RoutingRule in core config package
- Parsed from agent config YAML section
- Empty list by default (backward compatible)

### Implementation
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt`

---

## Story 11.2: Routing Rule Evaluation

**ID:** 11.2  
**Title:** Routing Rule Evaluation  
**Points:** 3  
**Priority:** P1

### User Story
- **As a** developer
- **I want** resolveAgent() to evaluate routing rules before other resolution logic
- **So that** routing rules take priority over defaults

### Acceptance Criteria
- [x] evaluateRoutingRules() method in DispatchService
- [x] First matching rule → usesAgent from agents map
- [x] Rules evaluated in priority descending order
- [x] If condition null → skip that filter (treat as match)
- [x] If useAgent not in agents map → warn log, fall through
- [x] No match → existing fallback logic unchanged
- [x] Unit tests: ifLabel match, ifLabelPrefix match, ifState match, ifPriority/ifPriorityMax, priority ordering, no match fallback

### Technical Notes
- Add evaluateRoutingRules() as step 0 in resolveAgent()
- Case-insensitive matching for labels and states
- Label prefix matches on any issue label

### Implementation
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`

---

## Story 11.3: Routing Integration and Edge Cases

**ID:** 11.3  
**Title:** Routing Integration & Edge Cases  
**Points:** 3  
**Priority:** P1

### User Story
- **As a** developer
- **I want** routing rules to integrate cleanly with stage configs and label-based overrides
- **So that** all agent resolution paths are predictable

### Acceptance Criteria
- [x] Routing rule match overrides default kind/command/model
- [x] Stage config agent provider still overrides routing (stage > routing)
- [x] Label `agent:` prefix overrides routing (label > routing)
- [x] Label `model:` prefix applies to routed agent too
- [x] Missing useAgent key → warn log, fallback
- [x] Routing rules with no matching conditions (all null) → match everything (lowest priority)
- [x] Unit tests for resolution priority chain

### Technical Notes
- Resolution order: stage agent > label agent > routing rule > default
- Model label applies after agent selection
- Log which rule matched and which agent resolved

### Implementation
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`
