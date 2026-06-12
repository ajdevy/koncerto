# Epic 13.04: Dashboard Authentication

**Story Points:** 5  
**Priority:** P1  
**Status:** Complete  

---

## Story 13.04.1: API Key Authentication

**ID:** 13.04.1  
**Title:** API Key Authentication  
**Points:** 2  
**Priority:** P1  

### User Story
- **As an** administrator
- **I want** API key authentication via `X-API-Key` header
- **So that** only authorized clients can access the dashboard API

### Acceptance Criteria
- [x] Security filter validates `X-API-Key` header on `/api/v1/**` endpoints
- [x] Configurable API keys via application configuration
- [x] Unauthorized requests return 401 with proper error response
- [x] Actuator endpoints exempted from API key requirement
- [x] Unit tests for authentication success and failure paths

### Technical Notes
- `SecurityConfig` with WebFlux Security filter chain
- API key filter implements `WebFilter` for reactive support
- Keys loaded from config with hot-reload capability
- Constant-time comparison to prevent timing attacks

### Implementation
- File: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/SecurityConfig.kt`
- Tests: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/SecurityConfigTest.kt`

---

## Story 13.04.2: OAuth2 JWT Support

**ID:** 13.04.2  
**Title:** OAuth2 JWT Support  
**Points:** 2  
**Priority:** P1  

### User Story
- **As an** administrator
- **I want** OAuth2 JWT support for enterprise authentication
- **So that** the dashboard integrates with existing identity providers

### Acceptance Criteria
- [x] OAuth2 resource server configuration with JWT decoder
- [x] Support for configurable issuer URI
- [x] JWT claim validation (issuer, audience, expiry)
- [x] Token-based session management for web UI
- [x] Bearer token authentication for API clients

### Technical Notes
- Spring Security OAuth2 resource server auto-configuration
- JWK set URI for key rotation support
- Custom `ReactiveAuthenticationManagerResolver` for multi-issuer support
- Role extraction from JWT claims

### Implementation
- File: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/SecurityConfig.kt`
- References: `src/main/resources/application.yml`

---

## Story 13.04.3: Role-Based Access Control

**ID:** 13.04.3  
**Title:** Role-Based Access Control  
**Points:** 1  
**Priority:** P1  

### User Story
- **As an** administrator
- **I want** role-based access control foundation
- **So that** different users have appropriate permissions

### Acceptance Criteria
- [x] Role definition with admin and viewer roles
- [x] Endpoint-level authorization based on roles
- [x] Default allow for actuator health endpoints
- [x] Extensible role hierarchy for future permissions
- [x] Proper error handling for forbidden access

### Technical Notes
- Roles encoded in JWT or mapped from API key configuration
- Reactive security with `@PreAuthorize` or path-based rules
- Admin role required for mutation endpoints
- Viewer role sufficient for read-only endpoints

### Implementation
- File: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/SecurityConfig.kt`
- Tests: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/SecurityConfigTest.kt`
