# Epic 2: Structured Logging

**Story Points:** 5  
**Priority:** P0  
**Status:** Complete  

---

## Story 2.1: StructuredLogger

**ID:** 2.1  
**Title:** StructuredLogger  
**Points:** 3  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** a StructuredLogger
- **So that** logs are machine-parseable

### Acceptance Criteria
- [ ] Key-value pair logging
- [ ] Support info, warn, failure levels
- [ ] Unit tests

### Technical Notes
- Use immutable design
- Accept list of sinks
- Include level, event name, context

### Implementation
- File: `koncerto-logging/src/main/kotlin/com/anomaly/koncerto/logging/StructuredLogger.kt`
- Tests: `koncerto-logging/src/test/kotlin/com/anomaly/koncerto/logging/StructuredLoggerTest.kt`

---

## Story 2.2: Log Sinks

**ID:** 2.2  
**Title:** Log Sinks  
**Points:** 2  
**Priority:** P0  

### User Story
- **As an** operator
- **I want** multiple log destinations
- **So that** logs go where needed

### Acceptance Criteria
- [ ] StderrSink writes to stderr
- [ ] FileSink writes to files
- [ ] CompositeSink fans out
- [ ] Unit tests

### Technical Notes
- StderrSink: Write JSON lines to stderr
- FileSink: Write to rotating log files
- CompositeSink: Fan out to multiple sinks

### Implementation
- File: `koncerto-logging/src/main/kotlin/com/anomaly/koncerto/logging/LogSinks.kt`
- Tests: `koncerto-logging/src/test/kotlin/com/anomaly/koncerto/logging/LogSinksTest.kt`
