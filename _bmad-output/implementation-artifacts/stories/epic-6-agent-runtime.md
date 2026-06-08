# Epic 6: Agent Runtime

**Story Points:** 25  
**Priority:** P0  
**Status:** In Progress  

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
- **So that** agent protocol messages are strongly typed

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

## Story 6.4: AgentRuntime Abstraction

**ID:** 6.4  
**Title:** AgentRuntime Abstraction  
**Points:** 3  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** an agent runtime abstraction
- **So that** multiple agent runtimes can be supported

### Acceptance Criteria
- [ ] AgentRuntime interface with spawn, send, stop, events
- [ ] AgentRuntimeFactory for creating runtime instances
- [ ] AgentRuntimeConfig data class for configuration
- [ ] Support agent.kind = codex | opencode
- [ ] Unit tests cover factory and interface

### Technical Notes
- Define common operations all runtimes must support
- Factory reads config.agent.kind to select implementation
- Each runtime handles its own protocol specifics

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRuntime.kt`
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRuntimeFactory.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRuntimeFactoryTest.kt`

---

## Story 6.5: CodexRuntime

**ID:** 6.5  
**Title:** CodexRuntime  
**Points:** 8  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** Codex runtime implementation
- **So that** Codex agents can be spawned and managed

### Acceptance Criteria
- [ ] Implements AgentRuntime interface
- [ ] Spawn codex app-server process
- [ ] Manage stdin/stdout pipes
- [ ] Handle Codex-specific JSON-RPC protocol
- [ ] Emit AgentEvent for each message type
- [ ] Extract token usage from turn completed events
- [ ] Thread-safe request ID generation
- [ ] Unit tests cover all cases

### Technical Notes
- Use ProcessBuilder for process spawning
- Read stdout/stderr in coroutine scope
- Channel-based event emission
- AtomicLong for request IDs

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/CodexRuntime.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/CodexRuntimeTest.kt`

---

## Story 6.6: OpencodeRuntime

**ID:** 6.6  
**Title:** OpencodeRuntime  
**Points:** 8  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** opencode runtime implementation
- **So that** opencode agents can be spawned and managed

### Acceptance Criteria
- [ ] Implements AgentRuntime interface
- [ ] Spawn opencode subprocess
- [ ] Manage stdin/stdout pipes
- [ ] Handle opencode-specific JSON-RPC protocol
- [ ] Emit AgentEvent for each message type
- [ ] Extract token usage from turn completed events
- [ ] Thread-safe request ID generation
- [ ] Unit tests cover all cases

### Technical Notes
- Research opencode CLI interface and protocol
- Use ProcessBuilder for process spawning
- Read stdout/stderr in coroutine scope
- Channel-based event emission
- AtomicLong for request IDs

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/OpencodeRuntime.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/OpencodeRuntimeTest.kt`

---

## Story 6.7: Turn Timeout

**ID:** 6.7  
**Title:** Turn Timeout  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** turn timeout
- **So that** hangs don't block forever

### Acceptance Criteria
- [ ] Configurable timeout per turn
- [ ] Kill process on timeout
- [ ] Unit tests

### Technical Notes
- Use withTimeout for turn execution
- Clean up process on timeout

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/TurnTimeout.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/TurnTimeoutTest.kt`

---

## Story 6.8: Stall Detection

**ID:** 6.8  
**Title:** Stall Detection  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** stall detection
- **So that** unresponsive agents are killed

### Acceptance Criteria
- [ ] Detect no-output conditions
- [ ] Terminate after timeout
- [ ] Unit tests

### Technical Notes
- Track last message timestamp
- Background coroutine checks for stalls

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/StallDetector.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/StallDetectorTest.kt`
