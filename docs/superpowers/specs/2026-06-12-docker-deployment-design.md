# Docker Deployment — Design Spec

**Date:** 2026-06-12
**Status:** Design

## Overview

Multi-stage Docker build producing a slim JRE runtime image for Koncerto, with docker-compose wiring PostgreSQL and Redis as backing services. Supports env-based configuration, health checks, volume mounts, and non-root execution.

## Motivation

Koncerto requires a consistent runtime environment across dev, CI, and production. Without Docker, operators must manually install JRE 21+, configure PostgreSQL/Redis, and manage startup ordering. Docker eliminates this friction and enables one-command deployment.

## Technical Design

### Dockerfile

Multi-stage build using Gradle as the build stage and `eclipse-temurin:21-jre` as the runtime stage:

```
Stage 1 (build): gradle:8.7-jdk21 → ./gradlew build
Stage 2 (runtime): eclipse-temurin:21-jre-alpine → copy fat JAR, create koncerto user, expose 8080
```

Health check: `curl -f http://localhost:8080/actuator/health` every 30s.

### docker-compose.yml

Three services:

| Service | Image | Purpose |
|---------|-------|---------|
| `app` | ghcr.io/anomaly/koncerto | Main application |
| `postgres` | postgres:16-alpine | Persistent issue state |
| `redis` | redis:7-alpine | Rate limiter backing store |

### Non-Root Execution

`USER koncerto:koncerto` in runtime stage. Working directory `/app` owned by koncerto user. Volume mounts for `/app/config` (workflow YAML) and `/app/logs`.

## Configuration

All secrets via environment variables (`.env` file or docker secrets):

| Variable | Default | Purpose |
|----------|---------|---------|
| `KONCERTO_CONFIG_PATH` | `/app/config/workflow.yml` | Workflow definition |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/koncerto` | PostgreSQL connection |
| `SPRING_DATA_REDIS_HOST` | `redis` | Redis connection |
| `MANAGEMENT_HEALTH_PROBES_ENABLED` | `true` | Kubernetes liveness probe support |

## Testing Strategy

- Docker Compose smoke test: `docker compose up --wait` then `curl localhost:8080/actuator/health`
- Image size validation: runtime image under 200MB
- Permission test: `docker run --rm koncerto whoami` returns `koncerto`

## Open Questions

- Should we support Kubernetes-native probes (liveness/readiness) directly in the Dockerfile, or keep it Compose-focused?
- Do we need health check retry logic for PostgreSQL readiness, or does Compose `depends_on` suffice?
