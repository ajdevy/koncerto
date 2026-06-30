# Story: Docker Launch Orphan Cleanup

**Epic:** Infrastructure / Demo Deploy  
**Status:** Implemented  
**Date:** 2026-06-30  

## Summary

Launch-time scanner that removes Koncerto orphan Docker entities while protecting the running stack, active agents, and persistent volumes.

## Acceptance Criteria

- [x] Investigation case file documents orphan families and protection rules
- [x] `DockerEntityPolicy` centralizes protected vs orphan classification
- [x] `DockerLaunchCleaner` scans containers, images, compose projects, dangling layers
- [x] Called from `KoncertoApplication.main` on JVM startup
- [x] Called from `scripts/koncerto-run.sh` via `docker-preflight-cleanup.sh`
- [x] `TargetProjectDeployer.cleanupOrphans()` delegates to shared cleaner
- [x] New `docker run` commands label `koncerto.managed-by=koncerto`
- [x] Unit tests for policy and cleaner

## Protected Resources

| Resource | Rule |
|----------|------|
| Running containers | Never removed (safety default) |
| Compose project `koncerto` | Protected |
| Volumes `koncerto-{workspace,data,logs,codex,claude}` | Never removed |
| Image `koncerto-agent:latest` | Never removed |

## Orphan Targets

| Resource | Rule |
|----------|------|
| Exited `koncerto-demo-*` containers | Removed |
| Exited `koncerto-agent-*` containers | Removed |
| Exited labeled `koncerto.managed-by=koncerto` | Removed |
| Exited `koncerto-*` (except `koncerto-koncerto-*`) | Removed |
| `koncerto-demo*` compose projects | `down --volumes` |
| `koncerto-demo-*` / `koncerto-test-*` images | Removed |
| Dangling image layers | Pruned |

## Files

- `koncerto-core/.../KoncertoDockerLabels.kt`
- `koncerto-deploy/.../DockerEntityPolicy.kt`
- `koncerto-deploy/.../DockerLaunchCleaner.kt`
- `scripts/docker-preflight-cleanup.sh`
- `_bmad-output/investigations/docker-orphan-cleanup-2026-06-30.md`
