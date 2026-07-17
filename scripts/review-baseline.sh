#!/usr/bin/env bash
# scripts/review-baseline.sh — Snapshot review-quality baseline metrics (Story 18.4)
#
# Reads the review telemetry aggregate from a running Koncerto dashboard and writes a
# markdown snapshot to _bmad-output/review-reports/baseline-<timestamp>.md.
#
# The point of this snapshot is to fix a "before" number BEFORE routing/context/gate
# changes land, so later epics can be judged against it rather than against vibes.
# Per the PRD, collect >= 30 reviews (or ~2 weeks) before treating a baseline as meaningful.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT_DIR="${PROJECT_ROOT}/_bmad-output/review-reports"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
BASE_URL="${KONCERTO_URL:-http://localhost:17348}"
PROJECT=""
WINDOW=30

usage() {
    cat <<EOF
Usage: $0 [--project <slug>] [--window <days>] [--url <base-url>] [--stdout]

  --project <slug>   Limit to one project (default: all projects)
  --window <days>    Look-back window in days (default: 30)
  --url <base-url>   Dashboard base URL (default: \$KONCERTO_URL or http://localhost:17348)
  --stdout           Print the report instead of writing a file
EOF
    exit 1
}

TO_STDOUT=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --project) PROJECT="$2"; shift 2 ;;
        --window)  WINDOW="$2"; shift 2 ;;
        --url)     BASE_URL="$2"; shift 2 ;;
        --stdout)  TO_STDOUT=true; shift ;;
        -h|--help) usage ;;
        *) echo "Unknown argument: $1" >&2; usage ;;
    esac
done

command -v curl >/dev/null 2>&1 || { echo "❌ curl is required" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "❌ python3 is required (JSON formatting)" >&2; exit 1; }

URL="${BASE_URL}/api/v1/review/baseline?window=${WINDOW}"
[[ -n "$PROJECT" ]] && URL="${URL}&project=${PROJECT}"

JSON=$(curl -fsS "$URL" 2>/dev/null) || {
    echo "❌ Could not reach ${URL}" >&2
    echo "   Is Koncerto running? Set KONCERTO_URL or pass --url." >&2
    exit 1
}

REPORT=$(python3 - "$JSON" "$PROJECT" "$WINDOW" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
project = sys.argv[2] or "all projects"
window = sys.argv[3]

def pct(x):
    return f"{x * 100:.1f}%"

lines = []
lines.append(f"# Review Baseline — {project}")
lines.append("")
lines.append(f"**Window:** last {window} days")
lines.append("")
lines.append("## Volume")
lines.append("")
lines.append("| Metric | Value |")
lines.append("|--------|-------|")
lines.append(f"| Total runs | {data['totalRuns']} |")
lines.append(f"| Reviewed (model invoked) | {data['reviewedRuns']} |")
lines.append(f"| Skipped by eligibility | {data['skippedRuns']} |")
lines.append(f"| Structured-parse fallbacks | {data['fallbackRuns']} |")
lines.append(f"| Findings produced | {data['totalFindings']} |")
lines.append(f"| Findings published | {data['publishedFindings']} |")
lines.append("")
lines.append("## Signal")
lines.append("")
lines.append("| Metric | Value | Target |")
lines.append("|--------|-------|--------|")
lines.append(f"| High-evidence outcome rate | {pct(data['highEvidenceRate'])} | >= 60% |")
lines.append(f"| False-positive rate | {pct(data['falsePositiveRate'])} | < 20% |")
lines.append(f"| Human-labeled findings | {data['humanLabeled']} | — |")
lines.append("")
lines.append("## Cost")
lines.append("")
lines.append("| Metric | Value |")
lines.append("|--------|-------|")
lines.append(f"| Total tokens | {data['totalTokens']:,} |")
lines.append(f"| Tokens per useful finding | {data['tokensPerUsefulFinding']:,.0f} |")
lines.append("")

if data.get('findingsByCategory'):
    lines.append("## Published findings by category")
    lines.append("")
    lines.append("| Category | Published | False positives |")
    lines.append("|----------|-----------|-----------------|")
    fp = data.get('fpByCategory', {})
    for cat, count in sorted(data['findingsByCategory'].items(), key=lambda kv: -kv[1]):
        lines.append(f"| {cat} | {count} | {fp.get(cat, 0)} |")
    lines.append("")

n = data['publishedFindings']
if n < 30:
    lines.append(f"> ⚠️ Only {n} published findings in this window — below the 30-review bar "
                 "the PRD sets for treating a baseline as meaningful. Keep collecting before "
                 "tuning thresholds or flipping gates to blocking.")
    lines.append("")

print("\n".join(lines))
PY
)

if [[ "$TO_STDOUT" == true ]]; then
    echo "$REPORT"
    exit 0
fi

mkdir -p "$OUTPUT_DIR"
SUFFIX="${PROJECT:-all}"
OUT_FILE="${OUTPUT_DIR}/baseline-${SUFFIX}-${TIMESTAMP}.md"
echo "$REPORT" > "$OUT_FILE"
echo "✅ Baseline written to ${OUT_FILE#$PROJECT_ROOT/}"
