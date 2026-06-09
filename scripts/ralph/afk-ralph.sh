#!/usr/bin/env bash
#
# Ralph loop — AFK driver.
#
# Runs ralph-once.sh repeatedly, advancing the backlog one issue per iteration,
# until the backlog is empty, the ready-frontier is exhausted (everything left is
# waiting on a human merge), or the iteration cap is hit.
#
# IMPORTANT — this loop self-terminates on <blocked/> because each issue becomes a
# PR awaiting YOUR merge, and the next issues only unblock once their blockers are
# CLOSED (i.e. merged). To run truly hands-off across the whole DAG you'd add
# CI-gated auto-merge for the safe categories (pure-domain, non-migration) — but
# do NOT auto-merge anything migration-guard touches.
#
# Run inside a Docker sandbox (`docker sandbox run ...`) if you want filesystem
# isolation and are comfortable raising the permission mode.
#
# Usage:  REPO=owner/repo ./scripts/ralph/afk-ralph.sh [max_iterations]   # default 15

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

MAX="${1:-15}"

for ((i = 1; i <= MAX; i++)); do
  echo "==================== iteration $i/$MAX ===================="
  ./scripts/ralph/ralph-once.sh
  rc=$?
  case "$rc" in
    0) : ;;                                                  # PR opened -> next issue
    2) echo "DONE — backlog empty after $i iteration(s)."; exit 0 ;;
    3) echo "BLOCKED — waiting on human merges. Merge PRs and re-run."; exit 0 ;;
    4) echo "migration-guard halted an issue; continuing to the next." ;;
    5) echo "an issue was escalated for a human decision; continuing to the next." ;;
    *) echo "FAILED (rc=$rc) — stopping for inspection."; exit 1 ;;
  esac
done

echo "Reached the $MAX-iteration cap."
