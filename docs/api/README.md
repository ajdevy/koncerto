# Koncerto — API Reference

**Version:** 1.0  
**Base URL:** `http://localhost:8080`  

---

## Overview

Koncerto exposes a REST API for monitoring orchestrator state and querying issue details. The API is designed for internal use and does not require authentication.

## Endpoints

### GET /api/v1/state

Returns a snapshot of the orchestrator's current state.

**Response:**

```json
{
  "running": [
    {
      "issueId": "abc-123",
      "issueIdentifier": "PROJ-42",
      "threadId": "thread-1",
      "turnId": "turn-1",
      "turnCount": 3,
      "inputTokens": 1500,
      "outputTokens": 500,
      "totalTokens": 2000,
      "url": "https://linear.app/myproject/issue/PROJ-42"
    }
  ],
  "retrying": [
    {
      "issueId": "def-456",
      "identifier": "PROJ-43",
      "attempt": 2,
      "dueAtMs": 1717850000000,
      "error": "agent_timeout"
    }
  ],
  "codexTotals": {
    "inputTokens": 15000,
    "outputTokens": 5000,
    "totalTokens": 20000,
    "secondsRunning": 3600
  },
  "rateLimits": {
    "requests_per_minute": "60"
  }
}
```

**Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `running` | `Array` | Currently executing issues |
| `running[].issueId` | `String` | Linear issue ID |
| `running[].issueIdentifier` | `String` | Human-readable identifier (e.g., PROJ-42) |
| `running[].threadId` | `String` | Agent thread identifier |
| `running[].turnId` | `String` | Current turn identifier |
| `running[].turnCount` | `Int` | Number of turns completed |
| `running[].inputTokens` | `Long` | Input tokens consumed |
| `running[].outputTokens` | `Long` | Output tokens generated |
| `running[].totalTokens` | `Long` | Total tokens consumed |
| `running[].url` | `String?` | Link to issue in Linear |
| `retrying` | `Array` | Issues scheduled for retry |
| `retrying[].attempt` | `Int` | Current attempt number |
| `retrying[].dueAtMs` | `Long` | Timestamp when retry should execute |
| `retrying[].error` | `String?` | Error message from last attempt |
| `codexTotals` | `Object` | Aggregate token usage |
| `rateLimits` | `Object` | Rate limit information |

---

### GET /api/v1/{identifier}

Returns details for a specific issue by its identifier.

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `identifier` | `String` | Issue identifier (e.g., PROJ-42) |

**Response (found):**

```json
{
  "issueId": "abc-123",
  "issueIdentifier": "PROJ-42",
  "threadId": "thread-1",
  "turnId": "turn-1",
  "turnCount": 3
}
```

**Response (not found):**

```json
{
  "error": "not_found"
}
```

---

### POST /api/v1/refresh

Triggers a manual refresh of the orchestrator state.

**Response:**

```json
{
  "status": "ok"
}
```

---

### GET /

Returns an HTML dashboard with live-refreshing state.

**Response:** HTML content

**Features:**

- Auto-refreshes every 5 seconds
- Shows running issues with token usage
- Shows retrying issues with attempt counts
- Displays aggregate metrics

---

## Error Handling

All endpoints return standard HTTP status codes:

| Code | Description |
|------|-------------|
| 200 | Success |
| 404 | Resource not found (for `/{identifier}`) |
| 500 | Internal server error |

Error responses include a JSON body with an `error` field:

```json
{
  "error": "error_message"
}
```

---

## Example Usage

### Get current state

```bash
curl http://localhost:8080/api/v1/state
```

### Get issue details

```bash
curl http://localhost:8080/api/v1/PROJ-42
```

### Trigger refresh

```bash
curl -X POST http://localhost:8080/api/v1/refresh
```

### Monitor with jq

```bash
# Watch running issues
watch -n 5 'curl -s http://localhost:8080/api/v1/state | jq ".running | length"'

# Get retrying issues
curl -s http://localhost:8080/api/v1/state | jq '.retrying[] | "\(.identifier) attempt \(.attempt)"'
```

---

## Dashboard UI

The root path (`/`) serves an HTML dashboard that automatically refreshes. The dashboard displays:

- **Running Issues:** Table with issue ID, thread, turns, token usage
- **Retrying Issues:** Table with issue ID, attempt, due time, error
- **Metrics:** Total tokens, seconds running, active slots

The dashboard uses JavaScript `fetch()` to poll the API every 5 seconds.

---

## Integration Examples

### Prometheus Metrics Exporter

```python
import requests
import json

def collect_metrics():
    state = requests.get("http://localhost:8080/api/v1/state").json()
    
    yield "koncerto_running_count", len(state["running"])
    yield "koncerto_retrying_count", len(state["retrying"])
    yield "koncerto_total_input_tokens", state["codexTotals"]["inputTokens"]
    yield "koncerto_total_output_tokens", state["codexTotals"]["outputTokens"]
    yield "koncerto_seconds_running", state["codexTotals"]["secondsRunning"]
```

### Slack Notification

```bash
#!/bin/bash
STATE=$(curl -s http://localhost:8080/api/v1/state)
RUNNING=$(echo $STATE | jq '.running | length')
RETRYING=$(echo $STATE | jq '.retrying | length')

if [ $RETRYING -gt 0 ]; then
    curl -X POST -H 'Content-type: application/json' \
        --data "{\"text\":\"Koncerto: $RETRYING issues retrying\"}" \
        $SLACK_WEBHOOK
fi
```

---

## Future Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/issues` | GET | List all tracked issues |
| `/api/v1/issues/{id}/attempts` | GET | Get attempt history |
| `/api/v1/metrics` | GET | Prometheus-compatible metrics |
| `/api/v1/config` | GET | Current configuration |
