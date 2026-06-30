# Investigation Case: Docker Orphan Entity Cleanup

**Status:** Concluded  
**Date:** 2026-06-30  
**Slug:** docker-orphan-cleanup  

## Hand-off Brief

Koncerto creates several Docker entity families during demo deploy, agent isolation, and compose orchestration. Only demo-deploy orphans (`koncerto-demo-*`) are cleaned periodically; agent containers wait 24h, launch scripts never pre-clean, and failed runs leave random-named exited containers plus dangling images. Critical stack volumes and running compose services must be protected on launch cleanup.

## Problem Statement

Between Koncerto launches, Docker entities accumulate on the host. The user needs launch-time discovery and safe removal of orphans, with explicit protection for critical resources (running stack, workspace volumes, active agents).

## Evidence Inventory

| Source | Status | Notes |
|--------|--------|-------|
| Live `docker ps -a` on host | **Confirmed** | 7 koncerto-related containers; 4 exited orphans |
| `TargetProjectDeployer.cleanupOrphans()` | **Confirmed** | koncerto-deploy/.../TargetProjectDeployer.kt:331-395 |
| `DockerContainerManager.pruneOldContainers()` | **Confirmed** | Only `koncerto-agent-*` older than 24h at app start |
| `scripts/koncerto-run.sh` | **Confirmed** | No pre-launch Docker cleanup |
| `OrphanedContainerCleanupScheduler` | **Confirmed** | Runs every 5 min after app start, not at shell launch |
| `AutoReviewOrchestrator.cleanupDemoDeploy()` | **Confirmed** | Per-issue cleanup after demo; skipped on crash |

## Confirmed Findings

### Orphan entity families

| Family | Created by | Current cleanup | Gap |
|--------|-----------|-----------------|-----|
| `koncerto-demo-<timestamp>` containers | `ContainerLifecycleManager.tryRunContainer()` | `cleanupOrphans()` + per-issue `cleanup()` | Missed if cleanup crashes; random-name failures not labeled |
| `koncerto-demo-*` images | `ContainerLifecycleManager.buildImage()` | `cleanupOrphans()` image filter | Dangling `<none>` layers not pruned |
| `koncerto-demo` compose projects/networks | `TargetProjectDeployer.deployWithCompose()` | `compose down` on deploy start + cleanup | Networks persist if down fails |
| `koncerto-agent-<ts>-<n>` containers | `DockerContainerManager.createContainer()` | `pruneOldContainers()` after 24h only | Recent exited agents linger |
| Random-name containers (`thirsty_almeida`, etc.) | Failed `docker run` without `--name` | **None** | No `koncerto.managed-by` label today |
| Dangling images (`<none>:<none>`) | Repeated `docker build` | **None** | 10+ dangling layers observed on host |
| Duplicate volumes (`koncerto_*` vs `koncerto-*`) | Compose project renames | **None** | Both prefixes coexist on host |
| Exited infra (`koncerto-minio`, `koncerto-fle52-pathfix`) | Manual/demo compose | **None** | Not matched by `koncerto-demo` filter |

### Critical entities (must NOT delete)

| Entity | Reason | Detection |
|--------|--------|-----------|
| Running `koncerto-app` / compose project `koncerto` | Main orchestrator stack | `com.docker.compose.project=koncerto` + status=running |
| Running `koncerto-agent-*` | Active agent work | name prefix + status=running |
| Volumes: `koncerto-workspace`, `koncerto-data`, `koncerto-logs`, `koncerto-codex`, `koncerto-claude` | Workspace, credentials, state | exact name or `koncerto_koncerto-*` prefix |
| Image `koncerto-agent:latest` | Agent runtime | exact tag match |
| Any **running** container | User may depend on it | status=running (default deny delete) |

## Hypotheses

| # | Hypothesis | Status | Resolution |
|---|-----------|--------|------------|
| H1 | `cleanupOrphans` runs at shell launch | **Refuted** | Only via Spring bean after JVM start |
| H2 | All demo containers use `koncerto-demo-*` names | **Refuted** | Failed runs can produce Docker random names |
| H3 | 24h agent prune is sufficient | **Refuted** | Exited agents <24h block ports and clutter `docker ps` |

## Fix Direction

1. **`DockerEntityPolicy`** — central rules for protected vs orphan candidates  
2. **`DockerLaunchCleaner`** — scan + remove at launch (containers, images, compose projects, dangling layers)  
3. **Call sites** — `KoncertoApplication.main`, `TargetProjectDeployer.cleanupOrphans()`, `scripts/koncerto-run.sh`  
4. **Label future runs** — `--label koncerto.managed-by=koncerto` on all Koncerto `docker run`  
5. **Do NOT prune protected volumes** — only remove unused demo compose volumes under `koncerto-demo*` projects  

## Reproduction / Verification Plan

```bash
# Before launch — inventory
docker ps -a --filter name=koncerto
docker images --filter reference=koncerto-demo-*
docker volume ls | grep koncerto

# Launch
./scripts/koncerto-run.sh --detach

# After launch — orphans removed, stack healthy
docker ps -a --filter status=exited --filter name=koncerto-demo
curl -s localhost:17348/actuator/health
```

**Confidence:** High on orphan families and gaps; Medium on random-name attribution until labels ship.
