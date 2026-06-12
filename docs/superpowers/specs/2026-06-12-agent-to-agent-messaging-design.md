# Agent-to-Agent Messaging — Design Spec

**Date:** 2026-06-12
**Status:** Design

## Overview

Asynchronous message bus enabling agents to communicate during complex workflows. Agents send structured messages that are persisted, routed, and acknowledged through a central message store, enabling multi-agent coordination without shared mutable state.

## Motivation

Multi-stage workflows (implement → review → QA) require agents to share context. Without messaging, each agent starts fresh with no knowledge of prior work. Agent-to-agent messaging enables agents to pass artifacts, raise blockers, request clarification, and coordinate without direct coupling or shared filesystem access.

## Technical Design

### Data Model

```json
{
  "id": "uuid",
  "sourceAgentId": "agent-1",
  "targetAgentId": "agent-2",
  "issueId": "KONC-123",
  "type": "context_passed|blocker_raised|clarification_requested",
  "payload": {},
  "status": "pending|acknowledged|failed",
  "createdAt": "ISO-8601",
  "acknowledgedAt": null
}
```

### Message Store

`AgentMessageStore` interface with two implementations:

| Implementation | Storage | Use Case |
|----------------|---------|----------|
| `InMemoryMessageStore` | `ConcurrentHashMap` | Development/testing |
| `SqliteMessageStore` | SQLite via sqlite-jdbc | Production persistence |

### Message Lifecycle

1. **Send**: Source agent calls `messageStore.send(msg)` → status = `pending`
2. **Route**: `DispatchService` checks pending messages for running issues → delivers on next poll
3. **Ack**: Target agent processes message → calls `messageStore.ack(msgId)` → status = `acknowledged`
4. **Retry**: Unacknowledged messages retried with exponential backoff (max 3 attempts)

### API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/v1/agent-messages` | List messages, filter by `issueId` or `status` |
| `POST` | `/api/v1/agent-messages` | Send a new message |
| `PUT` | `/api/v1/agent-messages/{id}/ack` | Acknowledge a message |

## Configuration

```yaml
koncerto:
  agent-messaging:
    store: sqlite
    retry-max-attempts: 3
    retry-backoff-ms: 5000
    max-pending-per-agent: 100
```

## Testing Strategy

- `InMemoryMessageStoreTest` — send/ack/retry lifecycle
- `SqliteMessageStoreTest` — persistence across simulated restarts
- `DispatchServiceMessagingTest` — agent message routed and delivered during orchestration
- `AgentMessageControllerTest` — HTTP CRUD for messages

## Open Questions

- Should messages have a TTL (time-to-live) to prevent stale messages from accumulating?
- Do we need message ordering guarantees (FIFO per agent pair) or is at-least-once delivery sufficient?
