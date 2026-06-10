# Epic 9: Application Assembly

**Story Points:** 8  
**Priority:** P0  
**Status:** Complete  

---

## Story 9.1: KoncertoApplication

**ID:** 9.1  
**Title:** KoncertoApplication  
**Points:** 1  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** a Spring Boot application entry point
- **So that** the application can be started and configured

### Acceptance Criteria
- [x] @SpringBootApplication annotation
- [x] @ComponentScan for all koncerto packages
- [x] main() function with args parsing
- [x] Detect --port arg for web type selection
- [x] Configure spring.main.web-application-type
- [x] Unit tests cover startup logic

### Technical Notes
- Use runApplication<KoncertoApplication>()
- Default to "none" web type
- Add web type to beginning of args

### Implementation
- File: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/KoncertoApplication.kt`
- Tests: `koncerto-app/src/test/kotlin/com/anomaly/koncerto/app/AppTest.kt`

---

## Story 9.2: Beans Configuration

**ID:** 9.2  
**Title:** Beans Configuration  
**Points:** 3  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** Spring bean configuration for all components
- **So that** dependency injection is properly wired

### Acceptance Criteria
- [x] Bean: StructuredLogger with file sink
- [x] Bean: WorkflowCache
- [x] Bean: CoroutineScope with SupervisorJob
- [x] Bean: ServiceConfig from workflow definition
- [x] Bean: WorkspaceManager with hook executor
- [x] Bean: LinearClient with GraphQL client
- [x] Bean: AgentRuntimeFactory (for Codex/opencode selection)
- [x] Bean: AgentRunner with runtime factory
- [x] Bean: RuntimeState from config
- [x] Bean: Orchestrator with all dependencies

### Technical Notes
- Use @Configuration class
- @Value for property injection
- Create logs directory if needed
- Load workflow from path at startup
- AgentRuntimeFactory selects runtime based on config.agent.kind

### Implementation
- File: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/Beans.kt`
- Tests: `koncerto-app/src/test/kotlin/com/anomaly/koncerto/app/BeansTest.kt`

---

## Story 9.3: CliRunner

**ID:** 9.3  
**Title:** CliRunner  
**Points:** 1  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** a CommandLineRunner to start the orchestrator
- **So that** agent processing begins on application startup

### Acceptance Criteria
- [x] Implement CommandLineRunner interface
- [x] Start orchestrator with coroutine scope
- [x] @Component annotation for auto-detection
- [x] Unit tests cover startup

### Technical Notes
- Use @Component for Spring auto-detection
- Inject Orchestrator and CoroutineScope
- Call orchestrator.start() in run()

### Implementation
- File: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/CliRunner.kt`
- Tests: `koncerto-app/src/test/kotlin/com/anomaly/koncerto/app/CliRunnerTest.kt`

---

## Story 9.4: Application Integration Test

**ID:** 9.4  
**Title:** Application Integration Test  
**Points:** 1  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** an integration test for the full application
- **So that** I can verify all components wire together

### Acceptance Criteria
- [x] Spring Boot test context loads
- [x] All beans are created successfully
- [x] No missing dependencies
- [x] Test context loads in under 5 seconds

### Technical Notes
- Use @SpringBootTest
- Verify context loads without errors
- Check for specific beans in context

### Implementation
- File: `koncerto-app/src/test/kotlin/com/anomaly/koncerto/app/AppTest.kt`

---

## Story 9.5: Agent Configuration Support

**ID:** 9.5  
**Title:** Agent Configuration Support  
**Points:** 2  
**Priority:** P0  

### User Story
- **As an** operator
- **I want** to configure which agent runtime to use
- **So that** I can choose between Codex and opencode

### Acceptance Criteria
- [x] agent.kind field in WORKFLOW.md config (codex or opencode)
- [x] codex.command configuration for Codex runtime
- [x] opencode.command configuration for opencode runtime
- [x] Validate agent.kind is one of: codex, opencode
- [x] Default to codex if not specified
- [x] Unit tests for configuration parsing

### Technical Notes
- Add agent.kind to ServiceConfig
- Add codex and opencode sub-configs
- Validate commands exist
- Support environment variable references

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt`
