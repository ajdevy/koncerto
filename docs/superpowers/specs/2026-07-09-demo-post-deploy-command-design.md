# Demo Post-Deploy Command Hook

## Problem

Every demo recording of FLE-52 (PromoMesh's email-code login) showed a generic "Не удалось отправить код" error, across many attempts, no matter what was fixed on the Brevo/secrets side. Root-cause investigation (reproducing Koncerto's exact deploy conditions in a throwaway container) traced it to two chained gaps:

1. `DB_URL`/`REDIS_URL` defaulted to `localhost`, unreachable from inside the app's own container — fixed as pure config (added to the existing `demoSecretsFile`, no code change).
2. Even with networking fixed, the database had no tables: **migrations had never been run** against the fresh postgres instance. Nothing in Koncerto's deploy flow runs a setup command after a container starts.

Both bugs collapsed to the same frontend fallback text (`data.detail || 'Не удалось отправить код.'`), because a raw 500 has no JSON `detail` field — which is why the real, more specific Brevo-activation work done earlier this session never actually showed up as the blocker: the app was failing before it ever reached the Brevo call.

Gap #2 needs *something* new — nothing runs post-deploy setup today.

## Approach

A small, generic, project-configured hook: an optional command string, run via `docker exec` against the just-started container once its health check passes, before the deploy is considered ready for recording. Koncerto has no knowledge of what the command does (migrations, seed data, cache warm — whatever the project needs) — it only knows *whether it succeeded*.

This is deliberately narrow. It does **not** attempt to detect migration tools/frameworks automatically (that would be exactly the kind of fragile, guessing-based feature worth avoiding) — the project/operator supplies the exact command, the same way `demoSecretsFile` already supplies exact secrets rather than Koncerto guessing which env vars a project needs.

## Components

**`ProjectConfig`** — new optional field `demoPostDeployCommand: String?`, parsed in `ServiceConfig.parseProjectConfig` from `demo_post_deploy_command` in `WORKFLOW.md` (mirroring how `demoSecretsFile`/`demo_secrets_file` is already parsed).

**`TargetProjectDeployer.deploy()`** — after `waitForHealthy()` succeeds and before returning `DeployResult.success(...)`: if a post-deploy command is configured, run it via `docker exec <containerId> sh -c "<command>"`. On non-zero exit, the deploy fails — the command's captured output becomes the error detail, flowing through the same failure path as any other deploy failure (health-check failure, build failure, etc.), so it's picked up by the existing demo-recovery loop without new plumbing.

**Idempotency** — no special-casing for "first deploy only." The compose infra's named volumes (`postgres_data`, `redis_data`) persist across recordings, so a command like `alembic upgrade head` is a no-op on every subsequent run. Simpler to always run it than to track whether it's needed.

## Error handling & edge cases

- **No command configured** (default) — deploy behaves exactly as today, zero behavior change for every other project.
- **Command fails** (non-zero exit) — deploy fails with captured stdout/stderr as the error detail; recording never starts against a container in a known-bad state. Matches the decision that a broken migration means the app's DB state is unreliable, so recording anyway would just reproduce the same silent-failure problem this investigation started from.
- **Command hangs** — bounded by a timeout (matching the pattern already used for other deploy subprocess calls in this codebase, e.g. `docker compose up` in `TargetProjectDeployer`), so a stuck migration can't stall the whole pipeline indefinitely.
- **Container becomes unhealthy between health-check and exec** — the `docker exec` call itself fails in that case (target process/container gone), surfacing as a deploy failure like any other transport-level exec error.

## Out of scope

- Auto-detecting migration tools/frameworks (Alembic, Rails, Prisma, etc.) — explicitly rejected in favor of an explicit, project-supplied command.
- Running the command anywhere other than immediately post-health-check, pre-recording (e.g., no periodic re-run, no rollback command).
- Retrying the command automatically on failure — a failed post-deploy command is a deploy failure, and follows the existing deploy-failure/recovery path rather than inventing a second one.
