# Epic 6: Agent Runtime

**Story Points:** 21  
**Priority:** P0  
**Status:** Complete  

---

## Story 6.1: AgentEvent

**ID:** 6.1  
**Title:** AgentEvent  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** typed event classes for agent lifecycle
- **So that** agent state changes are explicitly modeled

### Acceptance Criteria
- [ ] Sealed class hierarchy for all event types
- [ ] SessionStarted, StartupFailed events
- [ ] TurnCompleted, TurnFailed, TurnCancelled events
- [ ] TurnInputRequired, ApprovalAutoApproved events
- [ ] UnsupportedToolCall, Notification, OtherMessage, Malformed events
- [ ] Include timestamp and PID on all events
- [ ] Unit tests cover all event types

### Technical Notes
- Use sealed class for type safety
- Include Instant.now() for timestamps
- TokenUsage data class for completion events

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentEvent.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentEventTest.kt`

---

## Story 6.2: JsonRpcMessage

**ID:** 6.2  
**Title:** JsonRpcMessage  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** JSON-RPC message models
- **So that** Codex protocol messages are strongly typed

### Acceptance Criteria
- [ ] JsonRpcRequest with id, method, params
- [ ] JsonRpcResponse with id, result, error
- [ ] JsonRpcError with code, message, data
- [ ] JsonRpcNotification with method, params
- [ ] Sealed class for JsonRpcMessage types
- [ ] Unit tests cover all types

### Technical Notes
- Use kotlinx.serialization
- Include jsonrpc version field
- Support nullable params

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/JsonRpcMessage.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/JsonRpcMessageTest.kt`

---

## Story 6.3: JsonRpcFraming

**ID:** 6.3  
**Title:** JsonRpcFraming  
**Points:** 3  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to encode and decode JSON-RPC messages
- **So that** messages are properly serialized for transport

### Acceptance Criteria
- [ ] encodeRequest() serializes request to JSON string
- [ ] decodeAll() parses multiple JSON-RPC messages
- [ ] Handle newline-delimited messages
- [ ] Distinguish responses (has id + result/error) from notifications
- [ ] Ignore empty lines
- [ ] Unit tests cover all cases

### Technical Notes
- Use Json with ignoreUnknownKeys
- Append newline to encoded requests
- Parse each line independently

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/JsonRpcFraming.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/JsonRpcFramingTest.kt`

---

## Story 6.4: CodexAppServerClient

**ID:** 6.4  
**Title:** CodexAppServerClient  
**Points:** 10  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** a client to communicate with Codex app-server
- **So that** I can start agent processes and receive events

### Acceptance Criteria
- [ ] start() spawns process and begins reading
- [ ] send() writes JSON-RPC requests to stdin
- [ ] stop() closes process gracefully
- [ ] Read stdout for JSON-RPC messages
- [ ] Read stderr for debug logging
- [ ] Emit AgentEvent for each message type
- [ ] Handle process startup failures
- [ ] Extract token usage from turn completed events
- [ ] Thread-safe request ID generation
- [ ] Unit tests cover all cases

### Technical Notes
- Use ProcessBuilder for process spawning
- Read stdout/stderr in coroutine scope
- Channel-based event emission
- AtomicLong for request IDs

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/CodexAppServerClient.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/CodexAppServerClientTest.kt`

---

## Story 6.5: AgentRunner

**ID:** 6.5  
**Title:** AgentRunner  
**Points:** 4  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** a high-level runner for agent execution
- **So that** agent runs are orchestrated with workspace and hooks

### Acceptance Criteria
- [ ] run() executes agent with issue and prompt
- [ ] Create workspace for issue identifier
- [ ] Run afterCreate and beforeRun hooks
- [ ] Render prompt with issue context
- [ ] Send initialize, thread/start, turn/start messages
- [ ] Run afterRun hook on completion
- [ ] Emit events via Flow
- [ ] Return EmptyResult on success/failure
- [ ] Unit tests cover all cases

### Technical Notes
- Use WorkspaceManager for directory management
- Use PromptRenderer for template rendering
- Map Issue to template context
- SharedFlow for event emission

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTest.kt`
