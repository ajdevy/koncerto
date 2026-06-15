#!/usr/bin/env bash
# scripts/koncerto-run.sh — Build and run Koncerto with Docker Compose
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.yml"
WORKFLOW_FILE="${WORKFLOW_FILE:-${PROJECT_ROOT}/WORKFLOW.md}"
DEV_MODE=false
CLEAN_BUILD=false
DETACH=false
PROFILES=""
IMPLEMENTATION_MODEL="${KONCERTO_IMPLEMENTATION_MODEL:-codex-5.4}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Build and run Koncerto orchestration stack.

Options:
  --dev           Development mode: skip Gradle build, use pre-built JAR
  --clean         Clean rebuild: run gradle clean before build
  -d, --detach    Run docker compose in detached mode (no log tail)
  --profile <p>   Enable docker compose profile (e.g., --profile agent)
  --model <m>     Implementation agent model (default: codex-5.4)
  --codex-model <m>  Alias for --model
  --help          Show this help

Environment:
  WORKFLOW_FILE          Path to workflow markdown file (default: ./WORKFLOW.md)
  KONCERTO_PORT          Host port for koncerto-app (default: 17348)
  KONCERTO_IMPLEMENTATION_MODEL  Implementation agent model (default: codex-5.4)
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dev)             DEV_MODE=true; shift ;;
    --clean)           CLEAN_BUILD=true; shift ;;
    -d|--detach)       DETACH=true; shift ;;
    --profile)         shift; PROFILES="--profile $1"; shift ;;
    --model|--codex-model) shift; IMPLEMENTATION_MODEL="$1"; shift ;;
    --help)            usage ;;
    *)            echo "Unknown option: $1"; usage ;;
  esac
done

# Validate workflow file exists
if [[ ! -f "$WORKFLOW_FILE" ]]; then
  echo "ERROR: Workflow file not found: $WORKFLOW_FILE"
  echo "Set WORKFLOW_FILE env var or create WORKFLOW.md at project root"
  exit 1
fi

echo "[koncerto-run] Using workflow: $WORKFLOW_FILE"

# Step 1: Build JAR (unless --dev)
if [[ "$DEV_MODE" == "false" ]]; then
  echo "[koncerto-run] Building Koncerto JAR..."
  cd "$PROJECT_ROOT"
  if [[ "$CLEAN_BUILD" == "true" ]]; then
    ./gradlew clean --no-daemon
  fi
  ./gradlew :koncerto-app:bootJar -x test --no-daemon
  echo "[koncerto-run] JAR build complete"
else
  echo "[koncerto-run] Dev mode: skipping JAR build"
fi

# Step 2: Build agent Docker image
echo "[koncerto-run] Building koncerto-agent Docker image..."
docker build -f "$PROJECT_ROOT/Dockerfile.agent" -t koncerto-agent:latest "$PROJECT_ROOT"
echo "[koncerto-run] Agent image built"

# Step 3: Start docker compose
echo "[koncerto-run] Starting docker compose..."
cd "$PROJECT_ROOT"

# Export for docker compose
export WORKFLOW_FILE
export KONCERTO_PORT="${KONCERTO_PORT:-17348}"
export KONCERTO_IMPLEMENTATION_MODEL="${IMPLEMENTATION_MODEL}"

if [[ "$DETACH" == "true" ]]; then
  docker compose $PROFILES up -d --build
else
  docker compose $PROFILES up --build
fi

# Step 4: Wait for health (only in detached mode)
if [[ "$DETACH" == "true" ]]; then
  echo "[koncerto-run] Waiting for koncerto-app health..."
  MAX_WAIT=60
  START=$(date +%s)
  while true; do
    if docker compose exec -T koncerto-app wget -qO- "http://localhost:17348/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "[koncerto-run] koncerto-app is healthy"
      break
    fi
    NOW=$(date +%s)
    if (( NOW - START > MAX_WAIT )); then
      echo "[koncerto-run] ERROR: Health check timeout after ${MAX_WAIT}s"
      docker compose logs koncerto-app | tail -50
      exit 1
    fi
    sleep 2
  done
  echo "[koncerto-run] Koncerto is running at http://localhost:${KONCERTO_PORT}"
  echo "[koncerto-run] Use 'docker compose logs -f' to tail logs"
fi
