#!/usr/bin/env bash
# scripts/review-calibration.sh — Review calibration report (Story 22.2)
#
# Turns accumulated review telemetry into concrete tuning actions: which categories are
# producing false positives, whether the publication thresholds are set right, and whether
# the review is earning its token cost. Intended to be run on a schedule (or via the /loop
# skill) and read by a human before changing prompts or thresholds.
#
# Calibration is the closing step of the loop the AI-review research describes: submission →
# scoring → human feedback → calibration. Without the human labels this report is thin, so it
# tells you that instead of pretending otherwise.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT_DIR="${PROJECT_ROOT}/_bmad-output/review-reports"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
BASE_URL="${KONCERTO_URL:-http://localhost:17348}"
PROJECT=""
WINDOW=30
TO_STDOUT=false

usage() {
    cat <<EOF
Usage: $0 [--project <slug>] [--window <days>] [--url <base-url>] [--stdout]

Emits a calibration report with false-positive analysis and threshold recommendations.
EOF
    exit 1
}

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
command -v python3 >/dev/null 2>&1 || { echo "❌ python3 is required" >&2; exit 1; }

URL="${BASE_URL}/api/v1/review/baseline?window=${WINDOW}"
[[ -n "$PROJECT" ]] && URL="${URL}&project=${PROJECT}"

JSON=$(curl -fsS "$URL" 2>/dev/null) || {
    echo "❌ Could not reach ${URL}" >&2
    echo "   Is Koncerto running? Set KONCERTO_URL or pass --url." >&2
    exit 1
}

REPORT=$(python3 - "$JSON" "${PROJECT:-all projects}" "$WINDOW" <<'PY'
import json, sys

data = json.loads(sys.argv[1])
project, window = sys.argv[2], sys.argv[3]

def pct(x): return f"{x * 100:.1f}%"

published = data['publishedFindings']
labeled = data['humanLabeled']
fp_rate = data['falsePositiveRate']
heo_rate = data['highEvidenceRate']
by_cat = data.get('findingsByCategory', {}) or {}
fp_by_cat = data.get('fpByCategory', {}) or {}

out = []
out.append(f"# Review Calibration — {project}")
out.append("")
out.append(f"**Window:** last {window} days · **Published findings:** {published} · "
           f"**Human-labeled:** {labeled}")
out.append("")

# ---- Headline ----
out.append("## Headline")
out.append("")
out.append("| Metric | Value | Target | Status |")
out.append("|--------|-------|--------|--------|")
heo_ok = "✅" if heo_rate >= 0.60 else "⚠️"
fp_ok = "✅" if (labeled == 0 or fp_rate < 0.20) else "❌"
out.append(f"| High-evidence outcome rate | {pct(heo_rate)} | >= 60% | {heo_ok} |")
out.append(f"| False-positive rate | {pct(fp_rate) if labeled else 'n/a'} | < 20% | {fp_ok} |")
out.append(f"| Tokens per useful finding | {data['tokensPerUsefulFinding']:,.0f} | trend down | — |")
out.append(f"| Structured-parse fallbacks | {data['fallbackRuns']} | 0 | "
           f"{'✅' if data['fallbackRuns'] == 0 else '⚠️'} |")
out.append("")

# ---- Per-category FP analysis ----
if by_cat:
    out.append("## False positives by category")
    out.append("")
    out.append("| Category | Published | FP | FP rate |")
    out.append("|----------|-----------|----|---------|")
    rows = []
    for cat, count in by_cat.items():
        fp = fp_by_cat.get(cat, 0)
        rate = fp / count if count else 0.0
        rows.append((rate, cat, count, fp))
    for rate, cat, count, fp in sorted(rows, reverse=True):
        out.append(f"| {cat} | {count} | {fp} | {pct(rate)} |")
    out.append("")

# ---- Recommendations ----
out.append("## Recommended actions")
out.append("")
recs = []

if published < 30:
    recs.append(f"**Collect more data first.** Only {published} published findings in this "
                "window; the PRD sets a 30-review bar before tuning. Do not change thresholds yet.")

if labeled == 0 and published > 0:
    recs.append("**No human labels yet.** False-positive rate is unmeasurable, so the "
                "publication thresholds cannot be calibrated. Label findings via "
                "`POST /api/v1/review/findings/{id}/label` (accept|reject|false_positive) — "
                "this is the ground truth the whole loop depends on.")
elif labeled > 0 and labeled < max(10, published * 0.2):
    recs.append(f"**Thin label coverage** ({labeled} of {published} published findings). "
                "Treat the FP rate below as indicative, not decisive.")

if labeled >= 10 and fp_rate >= 0.20:
    recs.append(f"**FP rate {pct(fp_rate)} exceeds the 20% bar — raise thresholds.** Noisy "
                "findings are what destroys trust in AI review. Increase "
                "`review.publication_thresholds` for the worst categories below, then re-measure.")

if labeled >= 10 and fp_rate < 0.05 and published > 0:
    recs.append(f"**FP rate is very low ({pct(fp_rate)}) — you may be over-filtering.** "
                "Consider lowering thresholds slightly to recover recall, and check how many "
                "findings the gate is dropping.")

for cat, count in sorted(by_cat.items(), key=lambda kv: -kv[1]):
    fp = fp_by_cat.get(cat, 0)
    if count >= 5 and fp / count >= 0.30:
        recs.append(f"**Category `{cat}` has a {pct(fp / count)} FP rate** over {count} "
                    "findings. Tighten its contract in the prompt (make the evidence bar "
                    "explicit) or raise its severity threshold.")

if heo_rate < 0.60 and published >= 30:
    recs.append(f"**High-evidence rate {pct(heo_rate)} is below the 60% target.** Findings are "
                "being published but not acted on. This is usually a context problem, not a "
                "model problem — check that the context pack includes the issue intent and "
                "`review-invariants.md`, and that routing isn't sending trivial diffs to review.")

if data['fallbackRuns'] > 0:
    recs.append(f"**{data['fallbackRuns']} run(s) fell back to legacy verdict parsing** — the "
                "model did not emit a valid `review-findings` block. Those runs contribute no "
                "structured telemetry. Check the prompt version and the findings-block example.")

if data['skippedRuns'] > 0:
    saved = data['skippedRuns']
    recs.append(f"ℹ️ Eligibility skipped {saved} run(s) — the cheapest comment is the one never "
                "generated. Confirm none of them should have been reviewed.")

if not recs:
    recs.append("Nothing to tune: signal and cost are both within target. Keep collecting.")

for r in recs:
    out.append(f"- {r}")
out.append("")
out.append("---")
out.append("")
out.append("_After changing a prompt or threshold, bump the prompt `version:` in its "
           "frontmatter — every run records its prompt version, so versions are how you tell "
           "whether a change actually helped._")

print("\n".join(out))
PY
)

if [[ "$TO_STDOUT" == true ]]; then
    echo "$REPORT"
    exit 0
fi

mkdir -p "$OUTPUT_DIR"
OUT_FILE="${OUTPUT_DIR}/calibration-${PROJECT:-all}-${TIMESTAMP}.md"
echo "$REPORT" > "$OUT_FILE"
echo "✅ Calibration report written to ${OUT_FILE#$PROJECT_ROOT/}"
