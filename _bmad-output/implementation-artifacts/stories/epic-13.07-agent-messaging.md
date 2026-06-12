# Epic 13.07: Agent-to-Agent Messaging

**Story Points:** 8  
**Priority:** P2  
**Status:** Complete  

---

## Story 13.07.1: AgentMessageStore

**ID:** 13.07.1  
**Title:** AgentMessageStore  
**Points:** 3  
**Priority:** P2  

### User Story
- **As a** system
- **I want** an AgentMessageStore for message persistence
- **So that** messages between agents survive restarts

### Acceptance Criteria
- [x] `AgentMessageStore` interface with send, poll, ack, and list operations
- [x] In-memory implementation for development
- [x] SQLite-backed implementation for production persistence
- [x] `AgentMessage` event type with sender, recipient, payload, and timestamp
- [x] Message lifecycle: sent, delivered, acknowledged, failed

### Technical Notes
- SQLite storage using existing database infrastructure
- In-memory store uses `ConcurrentHashMap` with `AtomicLong` for IDs
- TTL-based cleanup for old messages
- Thread-safe operations for concurrent agent access

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/AgentMessageStore.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/AgentMessageStoreTest.kt`

---

## Story 13.07.2: Message Routing in DispatchService

**ID:** 13.07.2  
**Title:** Message Routing in DispatchService  
**Points:** 3  
**Priority:** P2  

### User Story
- **As a** system
- **I want** message routing in DispatchService
- **So that** messages are delivered to the correct agent

### Acceptance Criteria
- [x] Message routing based on recipient agent identifier
- [x] `/api/v1/agent-messages` endpoints for REST access
- [x] `POST /api/v1/agent-messages` to send a message
- [x] `GET /api/v1/agent-messages/{agentId}` to poll messages
- [x] Integration with existing DispatchService agent dispatch flow

### Technical Notes
- DispatchService checks for pending messages before dispatching
- Messages serialized as JSON for REST transport
- Routing table maps agent types to message queues
- Backpressure handling for high message volumes

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/DispatchService.kt`
- File: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`

---

## Story 13.07.3: Acknowledgment and Retry

**ID:** 13.07.3  
**Title:** Acknowledgment and Retry  
**Points:** 2  
**Priority:** P2  

### User Story
- **As a** system
- **I want** acknowledgment and retry mechanisms
- **So that** message delivery is reliable

### Acceptance Criteria
- [x] Agent acknowledges message via `POST /api/v1/agent-messages/{id}/ack`
- [x] Unacknowledged messages are retried with exponential backoff
- [x] Max retry count with dead letter state after exhaustion
- [x] Retry count and last error tracked per message
- [x] Monitoring metrics for message delivery success/failure rates

### Technical Notes
- Exponential backoff: 1s, 2s, 4s, 8s, 16s, max 30s
- Dead letter queue for messages exceeding max retries (3)
- Admin endpoint to view and replay dead letters
- Metrics exported via Micrometer for alerting

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/AgentMessageStore.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/AgentMessageStoreTest.kt`
