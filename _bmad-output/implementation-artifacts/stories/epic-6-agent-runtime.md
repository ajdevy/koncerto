# Epic 6: Agent Runtime

**Story Points:** 25  
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
- [x] Sealed class hierarchy for all event types
- [x] SessionStarted, StartupFailed events
- [x] TurnCompleted, TurnFailed, TurnCancelled events
- [x] TurnInputRequired, ApprovalAutoApproved events
- [x] UnsupportedToolCall, Notification, OtherMessage, Malformed events
- [x] Include timestamp and PID on all events
- [x] Unit tests cover all event types

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
- [x] JsonRpcRequest with id, method, params
- [x] JsonRpcResponse with id, result, error
- [x] JsonRpcError with code, message, data
- [x] JsonRpcNotification with method, params
- [x] Sealed class for JsonRpcMessage types
- [x] Unit tests cover all types

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
- [x] encodeRequest() serializes request to JSON string
- [x] decodeAll() parses multiple JSON-RPC messages
- [x] Handle newline-delimited messages
- [x] Distinguish responses (has id + result/error) from notifications
- [x] Ignore empty lines
- [x] Unit tests cover all cases

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
- [x] AgentRuntime interface with spawn, send, stop, events
- [x] AgentRuntimeFactory for creating runtime instances
- [x] AgentRuntimeConfig data class for configuration
- [x] Support agent.kind = codex | opencode
- [x] Unit tests cover factory and interface

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
- [x] Implements AgentRuntime interface
- [x] Spawn codex app-server process
- [x] Manage stdin/stdout pipes
- [x] Handle Codex-specific JSON-RPC protocol
- [x] Emit AgentEvent for each message type
- [x] Extract token usage from turn completed events
- [x] Thread-safe request ID generation
- [x] Unit tests cover all cases

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
- [x] Implements AgentRuntime interface
- [x] Spawn opencode subprocess
- [x] Manage stdin/stdout pipes
- [x] Handle opencode-specific JSON-RPC protocol
- [x] Emit AgentEvent for each message type
- [x] Extract token usage from turn completed events
- [x] Thread-safe request ID generation
- [x] Unit tests cover all cases

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
- [x] Configurable timeout per turn
- [x] Kill process on timeout
- [x] Unit tests

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
- [x] Detect no-output conditions
- [x] Terminate after timeout
- [x] Unit tests

### Technical Notes
- Track last message timestamp
- Background coroutine checks for stalls

### Implementation
- File: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/StallDetector.kt`
- Tests: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/StallDetectorTest.kt`
