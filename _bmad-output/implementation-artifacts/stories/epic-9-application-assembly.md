# Epic 9: Application Assembly

**Story Points:** 5  
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
- [ ] @SpringBootApplication annotation
- [ ] @ComponentScan for all koncerto packages
- [ ] main() function with args parsing
- [ ] Detect --port arg for web type selection
- [ ] Configure spring.main.web-application-type
- [ ] Unit tests cover startup logic

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
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** Spring bean configuration for all components
- **So that** dependency injection is properly wired

### Acceptance Criteria
- [ ] Bean: StructuredLogger with file sink
- [ ] Bean: WorkflowCache
- [ ] Bean: CoroutineScope with SupervisorJob
- [ ] Bean: ServiceConfig from workflow definition
- [ ] Bean: WorkspaceManager with hook executor
- [ ] Bean: LinearClient with GraphQL client
- [ ] Bean: AgentRunner
- [ ] Bean: RuntimeState from config
- [ ] Bean: Orchestrator with all dependencies

### Technical Notes
- Use @Configuration class
- @Value for property injection
- Create logs directory if needed
- Load workflow from path at startup

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
- [ ] Implement CommandLineRunner interface
- [ ] Start orchestrator with coroutine scope
- [ ] @Component annotation for auto-detection
- [ ] Unit tests cover startup

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
- [ ] Spring Boot test context loads
- [ ] All beans are created successfully
- [ ] No missing dependencies
- [ ] Test context loads in under 5 seconds

### Technical Notes
- Use @SpringBootTest
- Verify context loads without errors
- Check for specific beans in context

### Implementation
- File: `koncerto-app/src/test/kotlin/com/anomaly/koncerto/app/AppTest.kt`
