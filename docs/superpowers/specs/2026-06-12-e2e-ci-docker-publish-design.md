# E2E CI & Docker Publish — Design Spec

**Date:** 2026-06-12
**Status:** Design

## Overview

GitHub Actions workflows for end-to-end testing of the full Koncerto orchestration pipeline and automated Docker image publishing to GitHub Container Registry (ghcr.io) with semantic version tagging.

## Motivation

Without CI, regressions go undetected until runtime. Without automated Docker publishing, operators must build images manually. These two workflows close the gap: every PR gets validated end-to-end, and every tag produces a versioned, publishable Docker image.

## Technical Design

### e2e.yml

Trigger: `pull_request` to `main`, `workflow_dispatch`

Jobs:

1. **e2e-test** — runs `./gradlew :koncerto-e2e:e2eTest` on ubuntu-latest
   - Requires `opencode` CLI installed (from `opencode/install-opencode-action`)
   - Matrix: `ubuntu-latest`, `macos-latest` for platform coverage
   - Timeout: 10 minutes per test
   - Secrets: `OPENCODE_API_KEY` for model access

2. **unit-test** — runs `./gradlew build` (excludes e2e) with JDK 21
   - Matrix: JDK 21 on ubuntu-latest, macos-latest

### docker-publish.yml

Trigger: `push` to `main`, `push tags v*`

Jobs:

1. **build-and-publish** — multi-platform image build
   - Platforms: `linux/amd64`, `linux/arm64`
   - Tags: `ghcr.io/anomaly/koncerto:{version}`, `:latest` on main
   - Uses `docker/build-push-action` with cache from ghcr
   - Security scan with `aquasecurity/trivy-action`

2. **integration-check** — deploys published image via docker-compose, runs smoke test

### Versioning

- Main branch: `:main-{short-sha}` (latest)
- Tag `v1.2.3`: `:1.2.3`, `:1.2`, `:1`, `:latest`
- Semver extracted from git tag

## Testing Strategy

- Dry-run the e2e workflow with act (local GitHub Actions runner)
- Verify docker-publish pushes correct tags with `echo` mode before enabling real push
- Test matrix validation: verify both ubuntu and macos runners complete

## Open Questions

- Should docker-publish also run e2e tests before building, or are unit tests sufficient gate?
- Do we need a separate `docker-publish.yml` for release tags vs main branch builds?
