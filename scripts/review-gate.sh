#!/usr/bin/env bash
# scripts/review-gate.sh — Quality gate for Claude Code review findings
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REVIEW_SCRIPT="${PROJECT_ROOT}/scripts/review.sh"
REVIEW_DIR="${PROJECT_ROOT}/_bmad-output/review-reports"
OVERRIDE_FILE="${PROJECT_ROOT}/.bmad-review-override"

usage() {
  echo "Usage: $(basename "$0") [options]"
  echo ""
  echo "Check review findings and gate story completion."
  echo "Exits 0 if no CRITICAL findings, 1 if criticals exist."
  echo ""
  echo "Options:"
  echo "  --report <file>     Specify review report file (default: latest in review-reports/)"
  echo "  --override <reason> Bypass the gate with a documented reason"
  echo "  --help              Show this help."
  exit 0
}

REPORT_FILE=""
OVERRIDE_REASON=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report)   shift; REPORT_FILE="$1"; shift ;;
    --override) shift; OVERRIDE_REASON="$*"; break ;;
    --help)     usage ;;
    *)          echo "Unknown option: $1"; usage ;;
  esac
done

# Find latest review report if not specified
if [ -z "$REPORT_FILE" ]; then
  REPORT_FILE=$(ls -t "${REVIEW_DIR}"/*.md 2>/dev/null | head -1 || true)
  if [ -z "$REPORT_FILE" ]; then
    echo "No review report found. Running review first..."
    if [ -f "$REVIEW_SCRIPT" ]; then
      bash "$REVIEW_SCRIPT"
      REPORT_FILE=$(ls -t "${REVIEW_DIR}"/*.md 2>/dev/null | head -1 || true)
    fi
    if [ -z "$REPORT_FILE" ]; then
      echo "ERROR: No review report available and review script failed."
      exit 1
    fi
  fi
fi

echo "Gate checking: ${REPORT_FILE}"
echo ""

# Count critical findings from report
# Look for unchecked critical items: lines like "- [ ] " in the Critical Findings section
IN_CRITICAL=false
CRITICAL_COUNT=0
IN_WARNINGS=false
WARNING_COUNT=0

while IFS= read -r line; do
  # Section detection
  if [[ "$line" =~ ^##[[:space:]]*Critical ]]; then
    IN_CRITICAL=true
    IN_WARNINGS=false
    continue
  fi
  if [[ "$line" =~ ^##[[:space:]]*Warning ]]; then
    IN_CRITICAL=false
    IN_WARNINGS=true
    continue
  fi
  if [[ "$line" =~ ^##[[:space:]]*[A-Z] ]] && [ "$IN_CRITICAL" = true ] && [[ ! "$line" =~ ^##[[:space:]]*Critical ]]; then
    IN_CRITICAL=false
  fi

  if [ "$IN_CRITICAL" = true ] && [[ "$line" =~ ^[[:space:]]*- \[ \] ]]; then
    CRITICAL_COUNT=$((CRITICAL_COUNT + 1))
  fi
  if [ "$IN_WARNINGS" = true ] && [[ "$line" =~ ^[[:space:]]*- \[ \] ]]; then
    WARNING_COUNT=$((WARNING_COUNT + 1))
  fi
done < "$REPORT_FILE"

echo "Findings: ${CRITICAL_COUNT} critical, ${WARNING_COUNT} warnings"

# Handle override
if [ -n "$OVERRIDE_REASON" ]; then
  echo ""
  echo "⚠️  OVERRIDE by $(whoami) on $(date): ${OVERRIDE_REASON}"
  echo "⚠️  OVERRIDE by $(whoami) on $(date): ${OVERRIDE_REASON}" >> "$REPORT_FILE"
  mkdir -p "$(dirname "$OVERRIDE_FILE")"
  echo "[$(date)] $(whoami): ${OVERRIDE_REASON}" >> "$OVERRIDE_FILE"
  echo "Override logged. Gate bypassed."
  exit 0
fi

# Gate decision
if [ "$CRITICAL_COUNT" -gt 0 ]; then
  echo ""
  echo "❌ Gate FAILED — ${CRITICAL_COUNT} critical issue(s) found."
  echo ""
  echo "Run 'make review' to re-review after fixes, or use:"
  echo "  scripts/review-gate.sh --override \"<reason>\""
  echo "to bypass with a documented reason."
  exit 1
fi

echo ""
echo "✅ Gate PASSED — 0 critical issues."
exit 0
