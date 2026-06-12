# Epic 13.02: Prometheus Monitoring

**Story Points:** 5  
**Priority:** P2  
**Status:** Complete  

---

## Story 13.02.1: Micrometer Prometheus Exporter

**ID:** 13.02.1  
**Title:** Micrometer Prometheus Exporter  
**Points:** 2  
**Priority:** P2  

### User Story
- **As a** DevOps engineer
- **I want** Micrometer integration with Prometheus exporter
- **So that** I can collect and visualize system metrics

### Acceptance Criteria
- [x] Micrometer Prometheus registry dependency added
- [x] `/actuator/prometheus` endpoint exposed
- [x] JVM metrics (memory, threads, GC) auto-collected
- [x] SQLite metrics repository with Prometheus binders
- [x] Custom MeterRegistry configuration for tagging

### Technical Notes
- Use `micrometer-registry-prometheus` for Prometheus export
- Spring Boot actuator auto-configures the endpoint
- Common tags for environment, host, and application name
- Metrics flushed on configurable interval

### Implementation
- File: `koncerto-metrics/src/main/kotlin/com/anomaly/koncerto/metrics/MetricsConfig.kt`
- References: `build.gradle.kts`

---

## Story 13.02.2: Agent Run Counters and Business KPIs

**ID:** 13.02.2  
**Title:** Agent Run Counters and Business KPIs  
**Points:** 2  
**Priority:** P2  

### User Story
- **As a** DevOps engineer
- **I want** counters for agent runs, rate limiting, and circuit breakers
- **So that** I can monitor system health and performance

### Acceptance Criteria
- [x] Agent runtime counters for runs, success/failure rates
- [x] Rate limiter metrics (requests, bursts, throttled requests)
- [x] Circuit breaker state transition metrics
- [x] Business metrics (issues processed, time to completion)
- [x] Counter and timer metrics with appropriate tags

### Technical Notes
- Use `MeterRegistry.counter()` for count metrics
- Use `MeterRegistry.timer()` for latency metrics
- Use `MeterRegistry.gauge()` for state metrics
- Tag-based metric organization for filtering

### Implementation
- File: `koncerto-metrics/src/main/kotlin/com/anomaly/koncerto/metrics/AgentMetrics.kt`
- References: `koncerto-metrics/src/main/kotlin/com/anomaly/koncerto/metrics/RateLimiterMetrics.kt`

---

## Story 13.02.3: Custom Metrics Endpoint

**ID:** 13.02.3  
**Title:** Custom Metrics Endpoint  
**Points:** 1  
**Priority:** P2  

### User Story
- **As a** DevOps engineer
- **I want** the custom business KPIs exposed via `/actuator/prometheus`
- **So that** I can build dashboards for business-level monitoring

### Acceptance Criteria
- [x] Custom metrics registered in dedicated `koncerto-metrics` module
- [x] Core bindings for common metric patterns
- [x] Prometheus scrape endpoint returns all custom metrics
- [x] Actuator security permits `/actuator/prometheus` access
- [x] Metrics survive application restart when backed by SQLite

### Technical Notes
- `koncerto-metrics` module with core bindings and utilities
- Metrics registered eagerly at startup
- Prometheus text format for easy parsing
- Compatible with Grafana dashboards

### Implementation
- File: `koncerto-metrics/src/main/kotlin/com/anomaly/koncerto/metrics/CoreBindings.kt`
- Tests: `koncerto-metrics/src/test/kotlin/com/anomaly/koncerto/metrics/MetricsTest.kt`
