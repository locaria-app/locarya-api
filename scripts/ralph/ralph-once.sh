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
# Steps 1-3 each use an independent session — no context bleeds between them.
#   1. context-gatherer  → brief written to disk        (CTX_SID, discarded)
#   2. /tdd              → code committed on branch     (TDD_SID, discarded)
#   3. pr-runner         → PR opened from current branch (PR_SID, discarded)
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
# Usage: REPO=owner/repo ./scripts/ralph/ralph-once.sh [--verbose|-v] [--clean] [--model=<model>]

set -uo pipefail   # intentionally NO -e: a single failed step shouldn't be fatal

# --- Verbose flag + cleanup mode + model -----------------------------------
VERBOSE=0
CLEAN=0   # --clean: remove brief/log files after run (default: keep them)
MODEL="${RALPH_MODEL:-claude-sonnet-4-6}"
for arg in "$@"; do
  case "$arg" in
    --verbose|-v) VERBOSE=1 ;;
    --clean)      CLEAN=1 ;;
    --model=*)    MODEL="${arg#--model=}" ;;
  esac
done
vlog() { [ "$VERBOSE" -eq 1 ] && echo "[$(date '+%H:%M:%S')] $*" >&2 || true; }
elapsed() { local s=$(( $(date +%s) - $1 )); printf '%dm%02ds' $(( s/60 )) $(( s%60 )); }
# Remove ephemeral files only when --clean is passed; otherwise keep for inspection.
cleanup() { [ "$CLEAN" -eq 1 ] && rm -f "$@" || true; }
# ----------------------------------------------------------------------------

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"   # run from repo root so Claude Code picks up .claude/ and the project

# halt_in() — checks for a sentinel ONLY in assistant-authored text, never in the
# raw stream (which also contains our own prompt echoed back inside the initial
# user-turn JSON). A naive grep on the whole file would match the literal sentinel
# text we instruct the model to use, even when the model itself never said it —
# which is exactly what happened with <halt/>: it appears in our own instruction
# ("reply with exactly: <halt/>") and grep can't tell that apart from a real reply.
halt_in() {
  local raw="$1" sentinel="$2"
  jq -r 'select(.type=="assistant") | .message.content[]? | select(.type=="text") | .text' "$raw" 2>/dev/null \
    | grep -qF "$sentinel"
}

RALPH_TMP="$ROOT/.ralph-tmp"
mkdir -p "$RALPH_TMP"
TELEMETRY_LOG="$RALPH_TMP/ralph-telemetry.jsonl"

# --- Token/cost telemetry --------------------------------------------------
# Each claude invocation runs with --output-format stream-json --verbose so
# every event is emitted as a JSON line. We tee to a raw log file, parse the
# final "result" event for cost/turns/model, and append one record to
# TELEMETRY_LOG (JSONL). Review with: jq . .ralph-tmp/ralph-telemetry.jsonl
#
# run_claude <raw_log> <claude args...>
#   Streams output, tees raw events to $raw_log, prints readable text to stdout.
run_claude() {
  local raw_log="$1"; shift
  claude --model "$MODEL" --output-format stream-json --verbose "$@" \
    | tee "$raw_log" \
    | jq -r 'select(.type=="assistant" or .type=="text") | .content // .text // empty' 2>/dev/null \
    || true
}

# record_telemetry <step_label> <raw_log> <duration_seconds>
#   Extracts cost/turns/model breakdown from modelUsage and appends to TELEMETRY_LOG.
record_telemetry() {
  local label="$1" raw="$2" secs="$3"
  local result_line cost turns models model_costs record
  result_line="$(grep -m1 '"type":"result"' "$raw" 2>/dev/null || echo '{}')"
  cost="$(        printf '%s' "$result_line" | jq -r '.total_cost_usd              // "null"')"
  turns="$(       printf '%s' "$result_line" | jq -r '.num_turns                  // "null"')"
  # modelUsage is an object keyed by model name — extract all models used and per-model costs
  models="$(      printf '%s' "$result_line" | jq -r '.modelUsage // {} | keys | join(",")' 2>/dev/null || echo "${MODEL}")"
  model_costs="$( printf '%s' "$result_line" | jq -c  '.modelUsage // {} | to_entries | map({model:.key,cost_usd:.value.costUSD,input_tokens:.value.inputTokens,output_tokens:.value.outputTokens,cache_read:.value.cacheReadInputTokens,cache_write:.value.cacheCreationInputTokens})' 2>/dev/null || echo '[]')"
  # fallback: if modelUsage missing, report the requested model with null cost
  [ -z "$models" ] && models="$MODEL"
  record="$(jq -cn \
    --arg  ts          "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    --arg  issue       "${N:-?}" \
    --arg  step        "$label" \
    --arg  cost        "$cost" \
    --arg  turns       "$turns" \
    --arg  models      "$models" \
    --argjson model_costs "$model_costs" \
    --argjson secs     "$secs" \
    '{ts:$ts,issue:$issue,step:$step,cost_usd:$cost,turns:$turns,models:$models,model_costs:$model_costs,duration_s:$secs}')"
  echo "$record" >> "$TELEMETRY_LOG"
  vlog "telemetry [$label]: cost=$cost turns=$turns models=$models duration=${secs}s"
}
# ----------------------------------------------------------------------------

# --- Start every issue from a clean, up-to-date main ------------------------
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
echo ">> main is up-to-date"
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
TDD_SID="$(uuidgen)"   # fresh session for step 2 — discarded after tdd
PR_SID="$(uuidgen)"    # fresh session for step 3 — no /tdd context carried over
echo ">> issue #$N (migration=$MIG) ctx-session=$CTX_SID tdd-session=$TDD_SID pr-session=$PR_SID"
vlog "start: $(date '+%Y-%m-%d %H:%M:%S') | ctx=$CTX_SID tdd=$TDD_SID pr=$PR_SID"

# Create the feature branch now (from main) so the name is deterministic and
# both /tdd and pr-runner share the same branch without relying on the agent.
BRANCH="issue-$N"
if git switch -c "$BRANCH" 2>/dev/null; then
  echo ">> created branch: $BRANCH"
else
  git switch "$BRANCH"
  echo ">> resumed branch: $BRANCH (already existed)"
fi

BRIEF_FILE="$RALPH_TMP/issue-$N-brief.md"   # inside project tree, sandbox-writable, gitignored
CTX_RAW="$RALPH_TMP/issue-$N-ctx.raw.jsonl"  # raw stream-json events (sentinel + telemetry)

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
run_claude "$CTX_RAW" --session-id "$CTX_SID" --permission-mode acceptEdits \
  -p "Use the context-gatherer subagent on issue #$N to produce the implementation brief. \
Write the complete brief as a markdown file to $BRIEF_FILE (use the Bash tool: \
\`cat > '$BRIEF_FILE' << 'BRIEF_EOF'\n<brief content>\nBRIEF_EOF\`). \
After writing the file, print exactly: <brief-written/>. $GUARD"
record_telemetry "context-gatherer" "$CTX_RAW" "$(( $(date +%s) - STEP_START ))"
vlog "step 1/3 done ($(elapsed $STEP_START))"

if halt_in "$CTX_RAW" '<halt/>'; then
  echo ">> migration-guard halted #$N; labeled 'blocked'. Skipping."
  cleanup "$CTX_RAW" "$BRIEF_FILE"
  exit 4
fi

# Verify the brief was actually written before discarding the ctx session
if [ ! -s "$BRIEF_FILE" ]; then
  echo ">> context-gatherer did not write the brief to $BRIEF_FILE — aborting." >&2
  cleanup "$CTX_RAW" "$BRIEF_FILE"
  exit 1
fi
vlog "brief written: $(wc -c < "$BRIEF_FILE") bytes → $BRIEF_FILE"
cleanup "$CTX_RAW"  # raw events no longer needed after telemetry recorded

# 2. TDD — fresh session reading only the compact brief from disk.
#    No context from step 1 is inherited — this is the main token saving.
vlog "step 2/3: /tdd..."
STEP_START=$(date +%s)
TDD_RAW="$RALPH_TMP/issue-$N-tdd.raw.jsonl"
TDD_LOG="$RALPH_TMP/issue-$N-tdd.log"  # readable text — for <needs-human> grep
run_claude "$TDD_RAW" --session-id "$TDD_SID" --permission-mode acceptEdits -p "/tdd

Implementation brief for this issue is at $BRIEF_FILE — read it before starting. The brief \
already lists the existing patterns, domain terms, and files to touch — trust it instead of \
re-discovering them by re-reading unrelated files.

You are already on branch '$BRANCH' (created from main). Commit all work to this branch — \
do not create a new branch.

UNATTENDED RUN — no human is available to answer questions. Do not pause to ask. For \
each decision, choose the option most consistent with the PRD, the ADRs, CONTEXT.md and \
CLAUDE.md, and list every such choice under a '## Assumptions' heading in the PR body. \
Stay tests-first and follow the project's established testing approach; fully cover the \
tracer-bullet slice before widening scope. Always COMMIT the tests you write — never drop \
a test because it can't run in the current environment. \
When running tests mid-cycle (after each RED→GREEN step), scope the run to the spec you're \
working on (e.g. \`sbt "testOnly *PaymentServiceSpec"\`) rather than the full suite — this \
avoids recompiling/rerunning hundreds of unrelated tests on every small cycle. Only run the \
full suite (\`sbt test\`) once, as a final check before committing and opening the PR. \
Only if a choice is a business \
rule with no basis in the docs and is unsafe to guess, STOP before writing any code and \
reply with exactly: <needs-human>one-line question</needs-human>" \
  | tee "$TDD_LOG"
record_telemetry "/tdd" "$TDD_RAW" "$(( $(date +%s) - STEP_START ))"
vlog "step 2/3 done ($(elapsed $STEP_START))"

if halt_in "$TDD_RAW" '<needs-human>'; then
  Q="$(jq -r 'select(.type=="assistant") | .message.content[]? | select(.type=="text") | .text' "$TDD_RAW" 2>/dev/null | sed -nE 's@.*<needs-human>(.*)</needs-human>.*@\1@p' | head -1)"
  gh issue comment "$N" --body "Ralph paused — needs a human decision: $Q"
  gh issue edit "$N" --add-label needs-design
  echo ">> #$N escalated for a human decision; commented + labeled 'needs-design'. Skipping PR."
  cleanup "$TDD_RAW" "$TDD_LOG" "$BRIEF_FILE"
  exit 5
fi
cleanup "$TDD_RAW" "$TDD_LOG" "$BRIEF_FILE"

# 3. Open the PR. Fresh session — no /tdd context carried over.
#    BRANCH was created by the script (from main) before step 1 — the name is
#    deterministic; we pass it explicitly rather than reading git state.
#    pr-runner never merges and never pushes to main.
vlog "step 3/3: pr-runner..."
STEP_START=$(date +%s)
PR_RAW="$RALPH_TMP/issue-$N-pr.raw.jsonl"
run_claude "$PR_RAW" --session-id "$PR_SID" --permission-mode acceptEdits \
  -p "Use the pr-runner subagent for issue #$N. \
The code is already committed on branch '$BRANCH' — do not create a new branch or re-commit. \
Open the PR targeting main and stop — do not merge."
record_telemetry "pr-runner" "$PR_RAW" "$(( $(date +%s) - STEP_START ))"
cleanup "$PR_RAW"
vlog "step 3/3 done ($(elapsed $STEP_START))"
vlog "total: $(elapsed $LOOP_START)"

echo ">> #$N done. Review the PR; merge when CI is green."
exit 0
