#!/usr/bin/env bash
# scripts/smoke-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew test --no-daemon
./gradlew :koncerto-app:build -x test --no-daemon
echo "Build OK"
