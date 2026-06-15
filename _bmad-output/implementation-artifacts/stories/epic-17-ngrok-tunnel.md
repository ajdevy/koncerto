# Epic 17: ngrok Tunnel for Remote Dashboard Access

**Story Points:** 10
**Priority:** P1
**Status:** Complete

---

## Story 17.1: Dashboard on Custom Secure Port

**ID:** 17.1
**Title:** Dashboard on Custom Secure Port
**Points:** 3
**Priority:** P1

### User Story
- **As an** administrator deploying koncerto
- **I want** the dashboard to run on a custom non-standard port (17348)
- **So that** the dashboard is less discoverable by automated port scanners

### Acceptance Criteria
- [x] Dashboard server configured to listen on port 17348 instead of default 8080
- [x] `server.port` applied via `KONCERTO_DASHBOARD_PORT` env var (default: 17348) when `KONCERTO_WEB_TYPE=reactive` is set
- [x] docker-compose.yml updated: `ports` mapping changed from `"8080:8080"` to `"17348:17348"`
- [x] Dockerfile `EXPOSE 17348` and `HEALTHCHECK` URL updated from port 8080 to 17348
- [x] Environment variable `KONCERTO_DASHBOARD_PORT` (default: 17348) for port configuration
- [x] Existing behavior preserved: port default fallback via env var (set `KONCERTO_DASHBOARD_PORT=8080` for old behavior)
- [x] Build compiles and existing tests pass

### Technical Notes
- Spring Boot WebFlux uses `server.port` property — configure via `KONCERTO_DASHBOARD_PORT` env var in application.yml
- docker-compose.yml port mapping: `"17348:17348"` with env var injection
- Dockerfile HEALTHCHECK must reference port 17348
- Health endpoint (`/actuator/health`) should remain accessible on the custom port
- Existing 8080 convention should remain as default fallback for backward compat

### Implementation
- Update: `koncerto-app/src/main/resources/application.yml` (add `server.port` from env var)
- Update: `docker-compose.yml` (port mapping + env var)
- Update: `Dockerfile` (EXPOSE + HEALTHCHECK)
- Update: `README.md` (document port configuration)

---

## Story 17.2: ngrok Tunnel Configuration with OAuth

**ID:** 17.2
**Title:** ngrok Tunnel Configuration with OAuth
**Points:** 3
**Priority:** P1

### User Story
- **As an** administrator accessing koncerto remotely
- **I want** an ngrok tunnel secured with OAuth (Google/GitHub)
- **So that** only authorized users can reach the dashboard from outside the local network

### Acceptance Criteria
- [x] `ngrok.yml` configuration template created at `config/ngrok.example.yml` with:
  - Tunnel name `koncerto-dashboard`
  - `addr: localhost:17348`
  - `proto: http`
  - `inspect: false` (disable web UI)
- [x] OAuth provider configured supporting Google and/or GitHub with `allow_domains`
- [x] `config/ngrok.yml` created (gitignored, user customizes from example)
- [x] `config/ngrok.example.yml` checked into repo (sensitive values redacted)
- [x] `config/ngrok.yml` added to `.gitignore`
- [x] README updated with ngrok setup docs including OAuth configuration, prerequisites, and architecture diagram
- [x] README Running section updated to reference port 17348
- [x] Environment variables table updated with `KONCERTO_DASHBOARD_PORT` and `NGROK_AUTH_TOKEN`

### Technical Notes
- ngrok config structure:
  ```yaml
  version: "2"
  tunnels:
    koncerto-dashboard:
      proto: http
      addr: localhost:17348
      oauth:
        provider: google
        allow_emails:
          - admin@example.com
  ```
- OAuth must be configured with `allow_emails` or `allow_domains` to prevent open access
- ngrok auth token required — document how to set `NGROK_AUTH_TOKEN` env var
- Use `config/ngrok.example.yml` as a template with dummy values for version control

### Implementation
- Create: `config/ngrok.example.yml`
- Create: `config/ngrok.yml` (gitignored)
- Update: `.gitignore` (add `config/ngrok.yml`)
- Update: `README.md` (document ngrok setup)

---

## Story 17.3: Auto-Start Tunnel Lifecycle

**ID:** 17.3
**Title:** Auto-Start Tunnel Lifecycle
**Points:** 4
**Priority:** P1

### User Story
- **As an** administrator deploying koncerto
- **I want** the ngrok tunnel to start automatically when koncerto starts in dashboard mode
- **So that** remote access is available without manual tunnel setup

### Acceptance Criteria
- [x] ngrok managed as a Docker sidecar container in docker-compose.yml:
  - Uses `ngrok/ngrok:latest` image
  - Reads config from `./config/ngrok.yml` mounted at `/etc/ngrok.yml`
  - Sets `NGROK_AUTH_TOKEN` from environment (required, fails with clear message if missing)
  - Uses `NGROK_ADDR=koncerto:17348` to reach the koncerto container in Docker network
  - `depends_on: koncerto (condition: service_healthy)` ensures dashboard is up before tunnel starts
- [x] docker-compose network configuration puts ngrok on `koncerto-network` bridge
- [x] ngrok config uses `${NGROK_ADDR:-localhost:17348}` — works for both local dev and Docker
- [x] ngrok container has health check: `curl --fail http://localhost:4040/api/tunnels`
- [x] ngrok container uses Docker Compose profile `dashboard` — only starts with `--profile dashboard`
- [x] Graceful shutdown: `docker compose down` stops ngrok alongside koncerto
- [x] README documents setup, prerequisites, and how to get the tunnel URL from container logs

### Technical Notes
- Docker Compose sidecar approach is cleanest: ngrok runs as a separate container in the same network
- ngrok docker image: `ngrok/ngrok:latest` - supports config file via volume mount
- Command: `ngrok start --config /etc/ngrok.yml koncerto-dashboard`
- ngrok API (localhost:4040) can be used to fetch public URL programmatically
- The dashboard could later show the ngrok URL via the API endpoint
- Docker Compose profile `--profile dashboard` allows skipping ngrok when only orchestrator is needed

### Implementation
- Update: `docker-compose.yml` (add ngrok service)
- Update: `Dockerfile` (no changes needed — ngrok is sidecar)
- Update: `README.md` (ngrok setup + docker compose profile docs)

---

## Story 17.4: Dashboard Tunnel URL Display

**ID:** 17.4
**Title:** Dashboard Tunnel URL Display
**Points:** 2
**Priority:** P2

### User Story
- **As an** administrator accessing the dashboard
- **I want** the ngrok tunnel URL displayed on the dashboard UI or startup logs
- **So that** I know what URL to use without checking ngrok's admin interface

### Acceptance Criteria
- [x] API endpoint `GET /api/v1/tunnel` returns `{ "url": "https://...", "status": "active" | "inactive" }`
- [x] Dashboard UI shows the active ngrok tunnel URL when available (with clickable link)
- [x] ngrok tunnel URL fetched from ngrok API (http://ngrok:4040/api/tunnels) when running
- [x] Tunnel URL gracefully absent (no error, hidden) if ngrok is not reachable
- [x] Tunnel status refreshed every 15 seconds in the dashboard
- [x] Tunnel API returns `inactive` when ngrok API is unreachable (connection refused/timeout)
- [x] Tests for tunnel controller (inactive when unreachable, inactive for empty response, constructs with default URL)

### Technical Notes
- Spring WebClient or simple HTTP call to ngrok admin API (`http://ngrok:4040/api/tunnels`)
- `http://ngrok:4040` — the ngrok container hostname matches the docker-compose service name
- Cache the URL and refresh periodically (e.g., every 30s) since ngrok may restart
- Dashboard HTML template or API response could include the URL
- Graceful degradation: if ngrok API is unreachable, show "Tunnel: Not Connected"

### Implementation
- Create: `koncerto-dashboard/.../TunnelController.kt`
- Update: `koncerto-dashboard/.../templates/dashboard.html` (show tunnel URL)
- Tests: `koncerto-dashboard/src/test/.../TunnelControllerTest.kt`
