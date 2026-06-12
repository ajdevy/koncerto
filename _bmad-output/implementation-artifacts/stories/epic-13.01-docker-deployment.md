# Epic 13.01: Docker Deployment

**Story Points:** 3  
**Priority:** P1  
**Status:** Complete  

---

## Story 13.01.1: Multi-stage Dockerfile

**ID:** 13.01.1  
**Title:** Multi-stage Dockerfile  
**Points:** 1  
**Priority:** P1  

### User Story
- **As an** operations engineer
- **I want** a multi-stage Dockerfile using Temurin JRE
- **So that** the production image is minimal and secure

### Acceptance Criteria
- [x] Multi-stage build with Gradle build stage and slim JRE runtime stage
- [x] Use `eclipse-temurin:21-jre` as runtime base image
- [x] Copy built artifact from build stage
- [x] Expose port 8080 for application traffic
- [x] Graceful shutdown via SIGTERM handling

### Technical Notes
- Build stage uses Gradle with cache mounts for dependencies
- Runtime stage uses non-root user for security
- JVM optimized flags for container environments
- Layer caching for faster rebuilds

### Implementation
- File: `Dockerfile`
- References: `docker-compose.yml`

---

## Story 13.01.2: Docker Compose Setup

**ID:** 13.01.2  
**Title:** Docker Compose Setup  
**Points:** 1  
**Priority:** P1  

### User Story
- **As an** operations engineer
- **I want** a docker-compose.yml with PostgreSQL and Redis
- **So that** the full stack can be deployed with a single command

### Acceptance Criteria
- [x] `docker-compose.yml` with app, PostgreSQL, and Redis services
- [x] PostgreSQL with persistent volume mount
- [x] Redis with persistent volume mount
- [x] Environment-based configuration via `.env` file
- [x] Service dependency ordering with health checks
- [x] Resource limits for all services

### Technical Notes
- Use `postgres:16-alpine` for database
- Use `redis:7-alpine` for cache
- Named volumes for data persistence
- Network isolation via custom bridge network

### Implementation
- File: `docker-compose.yml`
- References: `Dockerfile`, `.env.example`

---

## Story 13.01.3: Health Checks and Security

**ID:** 13.01.3  
**Title:** Health Checks and Security  
**Points:** 1  
**Priority:** P1  

### User Story
- **As an** operations engineer
- **I want** health checks and non-root user in the container
- **So that** the deployment is production-ready and secure

### Acceptance Criteria
- [x] HEALTHCHECK instruction in Dockerfile using `/actuator/health`
- [x] Non-root `koncerto` user with UID 1000
- [x] Volume mounts for workflow config and logs
- [x] Read-only root filesystem after initialization
- [x] Config validation on container startup

### Technical Notes
- Health check interval and timeout tuned for Spring Boot startup
- Config directory mounted at `/app/config`
- Log directory mounted at `/app/logs`
- Capabilities dropped for security hardening

### Implementation
- File: `Dockerfile`
- References: `docker-compose.yml`, `src/main/resources/application.yml`
