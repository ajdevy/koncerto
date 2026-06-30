#!/usr/bin/env bash
# scripts/docker-preflight-cleanup.sh — Remove Koncerto orphan Docker entities before launch.
# Protects running stack containers and persistent volumes.
set -euo pipefail

echo "[docker-preflight] Scanning for Koncerto orphan containers..."

PROTECTED_VOLUMES="koncerto-workspace koncerto-data koncerto-logs koncerto-codex koncerto-claude"

is_protected_volume() {
  local vol="$1"
  for protected in $PROTECTED_VOLUMES; do
    if [[ "$vol" == "$protected" || "$vol" == "koncerto_${protected}" ]]; then
      return 0
    fi
  done
  return 1
}

# Remove exited koncerto-demo compose projects (not main 'koncerto' project)
if command -v docker >/dev/null 2>&1; then
  while IFS= read -r project; do
    [[ -z "$project" ]] && continue
    if [[ "$project" == koncerto-demo* ]]; then
      echo "[docker-preflight] Removing compose project: $project"
      docker compose -p "$project" down --remove-orphans --volumes 2>/dev/null || true
    fi
  done < <(docker compose ls --format '{{.Name}}' 2>/dev/null || true)

  # Remove exited orphan containers (never touch running containers)
  while IFS='|' read -r id name status image compose_project managed; do
    [[ -z "$id" ]] && continue
    if [[ "$status" == Up* ]]; then
      if [[ "$compose_project" == "koncerto" ]]; then
        continue
      fi
      continue
    fi
    if [[ "$name" == koncerto-koncerto-* ]]; then
      continue
    fi
    if [[ "$name" == koncerto-demo-* || "$name" == koncerto-agent-* || "$managed" == "koncerto" ]]; then
      echo "[docker-preflight] Removing container: $name ($id)"
      docker rm -f "$id" 2>/dev/null || true
      continue
    fi
    if [[ "$name" == koncerto-* ]]; then
      echo "[docker-preflight] Removing stale koncerto container: $name ($id)"
      docker rm -f "$id" 2>/dev/null || true
    fi
  done < <(docker ps -a --format '{{.ID}}|{{.Names}}|{{.Status}}|{{.Image}}|{{.Label "com.docker.compose.project"}}|{{.Label "koncerto.managed-by"}}' 2>/dev/null || true)

  # Remove stale koncerto-demo images
  while IFS= read -r tag; do
    [[ -z "$tag" ]] && continue
    if [[ "$tag" == koncerto-demo-* || "$tag" == koncerto-test-* ]]; then
      echo "[docker-preflight] Removing image: $tag"
      docker rmi -f "$tag" 2>/dev/null || true
    fi
  done < <(docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -E '^koncerto-(demo|test)-' || true)

  # Prune dangling build layers
  dangling=$(docker images -f dangling=true -q 2>/dev/null | wc -l | tr -d ' ')
  if [[ "$dangling" -gt 0 ]]; then
    echo "[docker-preflight] Pruning $dangling dangling image layer(s)"
    docker image prune -f >/dev/null 2>&1 || true
  fi

  echo "[docker-preflight] Cleanup complete (protected volumes preserved)"
else
  echo "[docker-preflight] docker not found, skipping"
fi
