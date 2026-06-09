# Epic 11: Agent Specialization Routing

**Story Points:** 8  
**Priority:** P1  
**Status:** Planned

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
- [ ] RoutingRule data class with ifLabel, ifLabelPrefix, ifState, ifPriority, ifPriorityMax, useAgent, priority fields
- [ ] routingRules field added to AgentProjectConfig
- [ ] parseRoutingRules() in ServiceConfig reads from YAML
- [ ] Rules sorted by priority descending at parse time
- [ ] Validation: useAgent must reference a key in agents map
- [ ] Unit tests for parsing: empty rules, single rule, multiple rules, missing useAgent

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
- [ ] evaluateRoutingRules() method in DispatchService
- [ ] First matching rule → usesAgent from agents map
- [ ] Rules evaluated in priority descending order
- [ ] If condition null → skip that filter (treat as match)
- [ ] If useAgent not in agents map → warn log, fall through
- [ ] No match → existing fallback logic unchanged
- [ ] Unit tests: ifLabel match, ifLabelPrefix match, ifState match, ifPriority/ifPriorityMax, priority ordering, no match fallback

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
- [ ] Routing rule match overrides default kind/command/model
- [ ] Stage config agent provider still overrides routing (stage > routing)
- [ ] Label `agent:` prefix overrides routing (label > routing)
- [ ] Label `model:` prefix applies to routed agent too
- [ ] Missing useAgent key → warn log, fallback
- [ ] Routing rules with no matching conditions (all null) → match everything (lowest priority)
- [ ] Unit tests for resolution priority chain

### Technical Notes
- Resolution order: stage agent > label agent > routing rule > default
- Model label applies after agent selection
- Log which rule matched and which agent resolved

### Implementation
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`
