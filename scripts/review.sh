#!/usr/bin/env bash
# scripts/review.sh — Post-story code review using Claude Code
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROMPT_FILE="${PROJECT_ROOT}/_bmad/review/prompt-template.md"
OUTPUT_DIR="${PROJECT_ROOT}/_bmad-output/review-reports"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
CLAUDE_CMD="claude"

ORIG_ARGS=("$@")

usage() {
  echo "Usage: $(basename "$0") [options]"
  echo ""
  echo "Run Claude Code review on changed code."
  echo ""
  echo "Options:"
  echo "  --files <paths>     Files to review (space-separated). Default: git diff against base branch."
  echo "  --output <dir>      Report output directory. Default: _bmad-output/review-reports/"
  echo "  --model <model>     Claude model to use. Default: claude-sonnet-4-20250514"
  echo "  --story <ref>       Story reference (e.g., 'epic-1-story-2'). Used in report filename."
  echo "  --annotate          Inject REVIEW annotations into source files after review."
  echo "  --verbose           Stream Claude Code output to terminal in real-time."
  echo "  --clean             Remove all REVIEW annotations from tracked files."
  echo "  --help              Show this help."
  exit 0
}

FILES=""
MODEL="claude-sonnet-4-20250514"
STORY_REF=""
VERBOSE=false
CLEAN=false
ANNOTATE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --files)    shift; FILES="$*"; break ;;
    --output)   shift; OUTPUT_DIR="$1"; shift ;;
    --model)    shift; MODEL="$1"; shift ;;
    --story)    shift; STORY_REF="$1"; shift ;;
    --annotate) ANNOTATE=true; shift ;;
    --verbose)  VERBOSE=true; shift ;;
    --clean)    CLEAN=true; shift ;;
    --help)     usage ;;
    *)          echo "Unknown option: $1"; usage ;;
  esac
done

# ── Clean mode ──────────────────────────────────────────────────────
if [ "$CLEAN" = true ]; then
  echo "Cleaning REVIEW annotations from tracked files..."
  find "${PROJECT_ROOT}" -type f \( -name "*.kt" -o -name "*.java" -o -name "*.kts" \) \
    -not -path "*/.git/*" -not -path "*/build/*" -not -path "*/.gradle/*" \
    -exec sed -i '' '/^[[:space:]]*\/\/ REVIEW /d' {} + 2>/dev/null || true
  find "${PROJECT_ROOT}" -type f \( -name "*.kt" -o -name "*.java" -o -name "*.kts" \) \
    -not -path "*/.git/*" -not -path "*/build/*" -not -path "*/.gradle/*" \
    -exec sed -i '' '/^[[:space:]]*\/\/ suggested:/d' {} + 2>/dev/null || true
  echo "Done."
  exit 0
fi

# ── Validate Claude Code CLI ────────────────────────────────────────
if ! command -v "$CLAUDE_CMD" &>/dev/null; then
  echo "ERROR: 'claude' command not found. Install Claude Code CLI first."
  echo "  npm install -g @anthropic-ai/claude-code"
  exit 1
fi

# ── Detect changed files ────────────────────────────────────────────
if [ -z "$FILES" ]; then
  BASE_BRANCH="${BASE_BRANCH:-main}"
  if git -C "${PROJECT_ROOT}" rev-parse --verify "$BASE_BRANCH" &>/dev/null; then
    CHANGED=$(git -C "${PROJECT_ROOT}" diff "$BASE_BRANCH"...HEAD --name-only --diff-filter=ACMRT \
      -- '*.kt' '*.java' '*.kts' '*.sh' '*.yml' '*.yaml' '*.properties' \
      | grep -v '/build/' | grep -v '.gradle/' | tr '\n' ' ')
  else
    CHANGED=$(git -C "${PROJECT_ROOT}" diff --name-only HEAD~1 \
      -- '*.kt' '*.java' '*.kts' '*.sh' '*.yml' '*.yaml' '*.properties' \
      | grep -v '/build/' | grep -v '.gradle/' | tr '\n' ' ')
  fi
  FILES="$CHANGED"
fi

if [ -z "$FILES" ] || [ "$FILES" = " " ]; then
  echo "No changed files to review."
  exit 0
fi

# ── Generate diff ───────────────────────────────────────────────────
DIFF=$(git -C "${PROJECT_ROOT}" diff "${BASE_BRANCH:-main}"...HEAD -- "${FILES}" 2>/dev/null || \
       git -C "${PROJECT_ROOT}" diff HEAD~1 -- "${FILES}" 2>/dev/null || true)

# ── Load prompt template ────────────────────────────────────────────
if [ ! -f "$PROMPT_FILE" ]; then
  echo "ERROR: Prompt template not found at $PROMPT_FILE"
  exit 1
fi

PROMPT=$(cat "$PROMPT_FILE")

mkdir -p "$OUTPUT_DIR"
REPORT_FILE="${OUTPUT_DIR}/${STORY_REF:+${STORY_REF}-}review-${TIMESTAMP}.md"

FULL_PROMPT="${PROMPT}

## Code Under Review

**Files:** ${FILES}

\`\`\`diff
${DIFF}
\`\`\`

Produce the review report and inline annotations now."

echo "Reviewing: ${FILES}"
echo "Model:     ${MODEL}"
echo "Report:    ${REPORT_FILE}"
echo ""

if [ "$VERBOSE" = true ]; then
  echo "$FULL_PROMPT" | "$CLAUDE_CMD" --model "$MODEL" --print 2>&1 | tee "$REPORT_FILE"
else
  echo "$FULL_PROMPT" | "$CLAUDE_CMD" --model "$MODEL" --print > "$REPORT_FILE" 2>&1
fi

CLAUDE_EXIT=$?
if [ $CLAUDE_EXIT -ne 0 ]; then
  echo "ERROR: Claude Code exited with code $CLAUDE_EXIT"
  exit $CLAUDE_EXIT
fi

echo ""
echo "Review complete. Report saved to: ${REPORT_FILE}"

# ── Inject inline annotations ──────────────────────────────────────
if [ "$ANNOTATE" = true ]; then
  echo ""
  echo "Injecting inline annotations..."
  grep '^// REVIEW \[' "$REPORT_FILE" 2>/dev/null | while IFS= read -r line; do
    FILE_PATH=$(echo "$line" | sed -n 's|// REVIEW \[.*\] \(.*\): [0-9]* — .*|\1|p')
    LINE_NUM=$(echo "$line" | sed -n 's|// REVIEW \[.*\] .*: \([0-9]*\) — .*|\1|p')
    SEVERITY=$(echo "$line" | sed -n 's|// REVIEW \[\(.*\)\] .*|\1|p')
    MESSAGE=$(echo "$line" | sed -n 's|// REVIEW \[.*\] .*: [0-9]* — \(.*\)|\1|p')
    TARGET_FILE="${PROJECT_ROOT}/${FILE_PATH}"
    if [ -n "$FILE_PATH" ] && [ -f "$TARGET_FILE" ] && [ -n "$LINE_NUM" ]; then
      REF_ID=$(echo "$line" | grep -o '\[[A-Z]*-[0-9]*\]' || echo "[${SEVERITY}]")
      ANNOTATION="// REVIEW ${REF_ID}: ${MESSAGE}"
      if [ "$(uname)" = "Darwin" ]; then
        sed -i '' "${LINE_NUM}i\\
${ANNOTATION}" "$TARGET_FILE"
      else
        sed -i "${LINE_NUM}i ${ANNOTATION}" "$TARGET_FILE"
      fi
      echo "  → ${FILE_PATH}:${LINE_NUM}"
    fi
  done
  echo "Annotations injected. Run 'scripts/review.sh --clean' to remove them."
fi
