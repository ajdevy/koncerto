# Dashboard Authentication — Design Spec

**Date:** 2026-06-12
**Status:** Design

## Overview

Authentication layer for the Koncerto dashboard and API endpoints using API key and OAuth2 JWT authentication. Secures the management UI while allowing read-only health check access for monitoring systems.

## Motivation

The dashboard exposes sensitive operational data: running agents, issue identifiers, token usage, and project configuration. Without authentication, anyone with network access can view or modify orchestration state. API key auth provides a low-friction option for script access, while OAuth2 JWT enables enterprise SSO integration.

## Technical Design

### Security Architecture

Spring Security filter chain with three tiers:

1. **Public**: `/actuator/health` — no auth required
2. **API Key**: `/api/v1/**` — `X-API-Key` header validated against configured key
3. **OAuth2**: Admin endpoints — JWT bearer token validated against OIDC issuer

### Components

- `SecurityConfig` — `@EnableWebFluxSecurity`, ordered filter chain
- `ApiKeyFilter` — extracts `X-API-Key` header, compares to `koncerto.auth.api-key`
- `JwtAuthConverter` — maps JWT claims to Spring Security authorities
- `RoleBasedAccess` — `ROLE_ADMIN` for config write, `ROLE_USER` for read

### Token Management

In-memory session tokens for browser-based dashboard usage, issued after API key or OAuth2 login.

## API Changes

| Method | Path | Auth | Change |
|--------|------|------|--------|
| `GET` | `/actuator/health` | None | Existing |
| `GET` | `/api/v1/state` | API key / JWT | New auth requirement |
| `POST` | `/api/v1/config` | JWT admin | New auth requirement |

## Configuration

```yaml
koncerto:
  auth:
    mode: api-key
    api-key: ${KONCERTO_API_KEY:-change-me}
    oauth2:
      issuer-uri: ${OAUTH2_ISSUER_URI}
      client-id: ${OAUTH2_CLIENT_ID}
```

## Testing Strategy

- `ApiKeyFilterTest` — valid/invalid/missing key returns correct HTTP status
- `SecurityConfigTest` — unauthenticated requests to `/api/v1/**` return 401
- `OAuth2Test` — mock JWT token with admin role grants config write access
- Integration: full request chain with `@WebFluxTest`

## Open Questions

- Should session tokens have an expiry, or persist for the dashboard session lifetime?
- Do we need per-project API keys, or is a single global key sufficient for v1?
