#!/usr/bin/env bash
#
# Ralph loop — single iteration (human-in-the-loop).
#
# Picks the next ready issue and drives it through ONE pass of your agent stack:
#   1. context-gatherer  (subagent) -> produces a compact brief written to a temp
#      file on disk; session is discarded after this step.
#      + migration-guard (subagent) when the issue touches the DB schema
#   2. /tdd              (skill, invoked explicitly) -> red-green-refactor
#      Starts a FRESH session and reads the brief file — no accumulated context
#      from step 1 carried over. This is the main token-budget optimisation:
#      the gatherer's full codebase reads never enter the /tdd context window.
#   3. pr-runner         (subagent) -> branch + commit + push + open PR (no merge)
#      Resumes the /tdd session (step 2 only), which is much smaller than the
#      old combined session that carried steps 1+2.
#
# `/tdd` is invoked as a LITERAL slash input (it lives at ~/.claude/skills/tdd/).
# Skills are model-invocable, but being explicit makes the load deterministic in
# an unattended run. If your /tdd skill takes an argument, change the line below
# to e.g.  -p "/tdd #$N".
#
# permission-mode acceptEdits: auto-accepts file edits; the repo's
# .claude/settings.json allow/deny list still applies (gh pr merge, push to main,
# and --force stay DENIED), so this is safe without --dangerously-skip-permissions.
#
# Portability: Bash 3.2 (macOS) + BSD tools — parsing uses sed -E, not grep -P.
#
# Exit codes (consumed by afk-ralph.sh):
#   0  issue processed, PR opened (or pr-runner reported nothing to do)
#   2  backlog empty (<complete/>)
#   3  frontier exhausted (<blocked/>) — merge open PRs, then re-run
#   4  migration-guard halted this issue (now labeled "blocked")
#   5  /tdd escalated a genuine blocker (now labeled "needs-design")
#   1  precondition failed (dirty tree, etc.) — stop and inspect
#
# Usage:  REPO=owner/repo ./scripts/ralph/ralph-once.sh [--verbose|-v]

set -uo pipefail   # intentionally NO -e: a single failed step shouldn't be fatal

# --- Verbose flag -----------------------------------------------------------
VERBOSE=0
for arg in "$@"; do
  case "$arg" in --verbose|-v) VERBOSE=1 ;; esac
done
vlog() { [ "$VERBOSE" -eq 1 ] && echo "[$(date '+%H:%M:%S')] $*" >&2 || true; }
elapsed() { local s=$(( $(date +%s) - $1 )); printf '%dm%02ds' $(( s/60 )) $(( s%60 )); }
# ----------------------------------------------------------------------------

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"   # run from repo root so Claude Code picks up .claude/ and the project

# --- Start every issue from a clean main -------------------------------------
# pr-runner leaves you on the issue's feature branch. Without resetting, the NEXT
# serial run would stack its work onto the previous issue's branch. Untracked
# files (e.g. not-yet-committed tooling) are fine; tracked uncommitted changes
# are not — those would carry across branches.
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo ">> tracked changes present — commit or stash before running. Aborting." >&2
  exit 1
fi
git switch main >/dev/null 2>&1 || git checkout main
git pull --ff-only --quiet || true
# -----------------------------------------------------------------------------

export REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"

LOOP_START=$(date +%s)
DECISION="$(./scripts/ralph/next-issue.sh)"
echo ">> decision: $DECISION"
case "$DECISION" in
  *"<complete/>"*) echo ">> backlog empty."; exit 2 ;;
  *"<blocked/>"*)  echo ">> frontier exhausted — merge open PRs, then re-run."; exit 3 ;;
esac

# Parse the sigil with sed -E (BSD-safe; no grep -P).
N="$(printf '%s' "$DECISION"  | sed -nE 's/.*<next issue=([0-9]+).*/\1/p')"
MIG="$(printf '%s' "$DECISION" | sed -nE 's/.*migration=([a-z]+).*/\1/p')"
CTX_SID="$(uuidgen)"   # session for step 1 only — discarded after brief is written
TDD_SID="$(uuidgen)"   # fresh session for steps 2+3
echo ">> issue #$N (migration=$MIG) ctx-session=$CTX_SID tdd-session=$TDD_SID"
vlog "start: $(date '+%Y-%m-%d %H:%M:%S') | ctx=$CTX_SID tdd=$TDD_SID"

RALPH_TMP="$ROOT/.ralph-tmp"
mkdir -p "$RALPH_TMP"
BRIEF_FILE="$RALPH_TMP/issue-$N-brief.md"   # inside project tree, sandbox-writable, gitignored
CTX_LOG="$RALPH_TMP/issue-$N-ctx.log"       # sentinel check only (read by shell, not agent)

# 1. Context (+ migration guard when relevant). The guard only REVIEWS; we act on
#    its verdict here by labeling the issue "blocked" and halting this iteration.
GUARD=""
if [ "$MIG" = "true" ]; then
  GUARD="Then use the migration-guard subagent on issue #$N. If its verdict is \
\"NEEDS HUMAN DECISION\" or it reports any Blocking finding, run \
\`gh issue edit $N --add-label blocked\` and then reply with exactly <halt/> and nothing else."
fi

vlog "step 1/3: context-gatherer$([ "$MIG" = true ] && echo ' + migration-guard' || true)..."
STEP_START=$(date +%s)
claude --session-id "$CTX_SID" --permission-mode acceptEdits \
  -p "Use the context-gatherer subagent on issue #$N to produce the implementation brief. \
Write the complete brief as a markdown file to $BRIEF_FILE (use the Bash tool: \
\`cat > '$BRIEF_FILE' << 'BRIEF_EOF'\n<brief content>\nBRIEF_EOF\`). \
After writing the file, print exactly: <brief-written/>. $GUARD" \
  | tee "$CTX_LOG"
vlog "step 1/3 done ($(elapsed $STEP_START))"

if grep -q '<halt/>' "$CTX_LOG"; then
  echo ">> migration-guard halted #$N; labeled 'blocked'. Skipping."
  rm -f "$CTX_LOG" "$BRIEF_FILE"
  exit 4
fi

# Verify the brief was actually written before discarding the ctx session
if [ ! -s "$BRIEF_FILE" ]; then
  echo ">> context-gatherer did not write the brief to $BRIEF_FILE — aborting." >&2
  rm -f "$CTX_LOG" "$BRIEF_FILE"
  exit 1
fi
vlog "brief written: $(wc -c < "$BRIEF_FILE") bytes → $BRIEF_FILE"
rm -f "$CTX_LOG"  # ctx session log no longer needed

# 2. TDD — fresh session reading only the compact brief from disk.
#    No context from step 1 is inherited — this is the main token saving.
vlog "step 2/3: /tdd..."
STEP_START=$(date +%s)
TDD_LOG="$(mktemp "${TMPDIR:-/tmp}/ralph-$N-tdd.XXXXXX")"
claude --session-id "$TDD_SID" --permission-mode acceptEdits -p "/tdd

Implementation brief for this issue is at $BRIEF_FILE — read it before starting.

UNATTENDED RUN — no human is available to answer questions. Do not pause to ask. For \
each decision, choose the option most consistent with the PRD, the ADRs, CONTEXT.md and \
CLAUDE.md, and list every such choice under a '## Assumptions' heading in the PR body. \
Stay tests-first and follow the project's established testing approach; fully cover the \
tracer-bullet slice before widening scope. Always COMMIT the tests you write — never drop \
a test because it can't run in the current environment. Only if a choice is a business \
rule with no basis in the docs and is unsafe to guess, STOP before writing any code and \
reply with exactly: <needs-human>one-line question</needs-human>" \
  | tee "$TDD_LOG"
vlog "step 2/3 done ($(elapsed $STEP_START))"

if grep -q '<needs-human>' "$TDD_LOG"; then
  Q="$(sed -nE 's@.*<needs-human>(.*)</needs-human>.*@\1@p' "$TDD_LOG")"
  gh issue comment "$N" --body "Ralph paused — needs a human decision: $Q"
  gh issue edit "$N" --add-label needs-design
  echo ">> #$N escalated for a human decision; commented + labeled 'needs-design'. Skipping PR."
  rm -f "$TDD_LOG" "$BRIEF_FILE"
  exit 5
fi
rm -f "$TDD_LOG" "$BRIEF_FILE"

# 3. Open the PR. Resumes TDD_SID (step 2 only — much smaller than the old
#    combined session). pr-runner never merges and never pushes to main.
vlog "step 3/3: pr-runner..."
STEP_START=$(date +%s)
claude --resume "$TDD_SID" --permission-mode acceptEdits \
  -p "Use the pr-runner subagent for issue #$N. Open the PR and stop — do not merge."
vlog "step 3/3 done ($(elapsed $STEP_START))"
vlog "total: $(elapsed $LOOP_START)"

echo ">> #$N done. Review the PR; merge when CI is green."
exit 0
