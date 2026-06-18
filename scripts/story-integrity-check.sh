#!/usr/bin/env bash
# scripts/story-integrity-check.sh — Pre-merge gate for story/epic task descriptions
#
# Ensures that during implementation:
#   1. Task descriptions (User Story, Acceptance Criteria text, Technical Notes,
#      story metadata) are NOT modified from their state when the story was in "todo"
#   2. No new implementation-relevant comments were added to story files
#
# Only these changes are allowed:
#   - Checkbox status toggles (- [ ] ↔ - [x])
#   - Status field updates (e.g., "In Progress" → "Complete")
#   - Implementation section modifications (file paths, test paths)
#
# Usage:
#   scripts/story-integrity-check.sh
#   scripts/story-integrity-check.sh --base origin/main
#   scripts/story-integrity-check.sh --files "_bmad-output/.../epic-7.md"
#   scripts/story-integrity-check.sh --override "Rationale for bypassing"
#   scripts/story-integrity-check.sh --verbose

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STORY_DIR="${PROJECT_ROOT}/_bmad-output/implementation-artifacts/stories"
DEFAULT_BASE="main"
BASE_REF="$DEFAULT_BASE"
FILES=""
VERBOSE=false
OVERRIDE_REASON=""
HAS_VIOLATION=false

usage() {
  echo "Usage: $(basename "$0") [options]"
  echo ""
  echo "Check story file integrity before merging."
  echo "Verifies task descriptions weren't modified and no comments were added."
  echo ""
  echo "Options:"
  echo "  --base <ref>       Base ref to compare against (default: main)"
  echo "  --files <paths>    Story files to check (space-separated). Default: git diff."
  echo "  --override <text>  Bypass the gate with a documented reason"
  echo "  --verbose          Show detailed diff output"
  echo "  --help             Show this help."
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)     shift; BASE_REF="$1"; shift ;;
    --files)    shift; FILES="$*"; break ;;
    --override) shift; OVERRIDE_REASON="$*"; break ;;
    --verbose)  VERBOSE=true; shift ;;
    --help)     usage ;;
    *)          echo "Unknown option: $1"; usage ;;
  esac
done

# ── Auto-detect changed story files ──────────────────────────────────
if [ -z "$FILES" ]; then
  FILES=$(git -C "$PROJECT_ROOT" diff "$BASE_REF"...HEAD --name-only -- \
    '_bmad-output/implementation-artifacts/stories/*.md' 2>/dev/null || true)
  if [ -z "$FILES" ]; then
    # Fallback: working tree diff (no branch context)
    FILES=$(git -C "$PROJECT_ROOT" diff --name-only -- \
      '_bmad-output/implementation-artifacts/stories/*.md' 2>/dev/null || true)
  fi
fi

if [ -z "$FILES" ]; then
  echo "✅ No story files changed."
  exit 0
fi

echo "Checking story file integrity against \`${BASE_REF}\`..."
echo ""

# ── Normalize: strip allowed-only changes ────────────────────────────
# 1. Normalize checkbox status (- [x] → - [ ])
# 2. Remove Status field line
# 3. Remove Implementation section (### Implementation to next ## or EOF)
normalize() {
  awk '
    /^### Implementation/ { in_impl = 1; next }
    /^## / && in_impl { in_impl = 0 }
    !in_impl { print }
  ' | sed \
    -e 's/- \[x\]/- [ ]/g' \
    -e '/^\*\*Status:\*\*/d'
}

# ── Check a single file ──────────────────────────────────────────────
check_file() {
  local file="$1"
  local relpath="${file#$PROJECT_ROOT/}"

  local base_content
  base_content=$(git -C "$PROJECT_ROOT" show "$BASE_REF:$relpath" 2>/dev/null || true)

  if [ -z "$base_content" ]; then
    echo "  ⚠️  New file (no baseline), skipping: ${relpath}"
    return 0
  fi

  local normalized_base
  normalized_base=$(echo "$base_content" | normalize)
  local normalized_current
  normalized_current=$(normalize < "$file")

  if [ "$normalized_base" != "$normalized_current" ]; then
    echo "  ❌ VIOLATION: ${relpath}"
    HAS_VIOLATION=true
    if [ "$VERBOSE" = true ]; then
      diff -u <(echo "$normalized_base") <(echo "$normalized_current") || true
    fi
  else
    echo "  ✅ ${relpath}"
  fi
}

# ── Run checks ───────────────────────────────────────────────────────
for file in $FILES; do
  # If $FILES came from git diff, paths are relative to project root
  if [ -f "$PROJECT_ROOT/$file" ]; then
    check_file "$PROJECT_ROOT/$file"
  elif [ -f "$file" ]; then
    check_file "$file"
  fi
done

echo ""

# ── Handle override ──────────────────────────────────────────────────
if [ "$HAS_VIOLATION" = true ] && [ -n "$OVERRIDE_REASON" ]; then
  OVERRIDE_FILE="${PROJECT_ROOT}/.bmad-story-override"
  echo "⚠️  OVERRIDE by $(whoami) on $(date): ${OVERRIDE_REASON}"
  mkdir -p "$(dirname "$OVERRIDE_FILE")"
  echo "[$(date)] $(whoami): ${OVERRIDE_REASON}" >> "$OVERRIDE_FILE"
  echo "Override logged to ${OVERRIDE_FILE}. Gate bypassed."
  exit 0
fi

# ── Gate decision ────────────────────────────────────────────────────
if [ "$HAS_VIOLATION" = true ]; then
  echo "❌ Gate FAILED — Task descriptions were modified or comments were added."
  echo ""
  echo "Only these changes are allowed in story files during implementation:"
  echo "  ✓ Checkbox status toggles (- [ ] ↔ - [x])"
  echo "  ✓ Status field updates (e.g., \"In Progress\" → \"Complete\")"
  echo "  ✓ Implementation section changes"
  echo ""
  echo "All other changes (User Story, Acceptance Criteria text, Technical Notes,"
  echo "story metadata, new sections, added commentary) are BLOCKED."
  echo ""
  echo "To see the exact diff, re-run with: $(basename "$0") --verbose"
  echo "To bypass: $(basename "$0") --override \"<reason>\""
  exit 1
fi

echo "✅ Gate PASSED — All story files intact."
exit 0
