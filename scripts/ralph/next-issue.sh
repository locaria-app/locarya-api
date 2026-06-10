#!/usr/bin/env bash
#
# Ralph loop — deterministic "what's next" gate.
#
# Reads the GitHub backlog and emits ONE sigil on stdout:
#   <next issue=N migration=true|false/>   an unblocked, agent-ready issue
#   <blocked/>                              open issues exist, but none are ready
#   <complete/>                             no open agent-ready issues remain
#
# Readiness predicate (matches the issue-scheduler agent's rules):
#   state == open
#   AND label "ready-for-agent" present
#   AND NOT labeled "blocked" / "needs-design"
#   AND has a "## Acceptance criteria" section  (i.e. a real implementable issue,
#       not the PRD/epic — the PRD has no AC checklist)
#   AND no open PR already exists for the issue (branch "issue-<N>-*", per pr-runner)
#   AND every "#N" under the issue's "## Blocked by" section is CLOSED
#
# Closing convention: pr-runner opens PRs with "Closes #N", so a merged PR
# closes the issue — that closed state IS the "blocker satisfied" signal.
#
# Portability: written for Bash 3.2 (macOS default) — no `mapfile`. Uses only
# BSD-compatible grep (no -P/\K); "#N" is matched as "#<digits>" then stripped.
#
# Usage:  REPO=owner/repo ./scripts/ralph/next-issue.sh
#         (REPO defaults to the current repo via `gh repo view`)

set -euo pipefail

REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"

# Candidates: open + ready-for-agent, excluding blocked / needs-design, AND only
# real implementable issues — those carrying a "## Acceptance criteria" section.
# This drops the PRD/epic (#1), which has no AC checklist, without relying on the
# title or any extra label. (Alternative one-liner if you ever prefer it:
#   select(.title | startswith("PRD") | not)  )
CANDS=()
while IFS= read -r line; do
  [ -n "$line" ] && CANDS+=("$line")
done < <(
  gh issue list --repo "$REPO" --state open --label ready-for-agent --limit 200 \
    --json number,title,labels,body \
    --jq '.[]
            | select((.labels | map(.name) | any(. == "blocked" or . == "needs-design")) | not)
            | select((.body // "") | contains("## Acceptance criteria"))
            | .number' \
  | sort -n
)
[ ${#CANDS[@]} -eq 0 ] && { echo "<complete/>"; exit 0; }

is_closed() {
  [ "$(gh issue view "$1" --repo "$REPO" --json state -q .state)" = "CLOSED" ]
}

# Cache all open PR branch names once — avoids repeated gh calls and
# intermittent auth/keychain failures on rapid successive requests.
OPEN_PR_BRANCHES="$(gh pr list --repo "$REPO" --state open --json headRefName \
  -q '.[].headRefName' 2>/dev/null || true)"

has_open_pr() {
  printf '%s\n' "$OPEN_PR_BRANCHES" | grep -qE "^(feat/)?issue-$1(-|$)"
}

# Extract the "#N" references found under the "## Blocked by" heading only.
# BSD grep lacks -P/\K, so match "#<digits>" and strip the leading '#'.
# Matching "#[0-9]+" (not bare "[0-9]+") avoids grabbing numbers from prose
# like "#4 (Slice 3: ...)" — only the #4 is returned, not the 3.
blockers_of() {
  gh issue view "$1" --repo "$REPO" --json body -q .body \
    | awk '/^## *Blocked by/{f=1; next} /^## /{f=0} f' \
    | grep -oE '#[0-9]+' | tr -d '#' || true
}

for n in "${CANDS[@]}"; do
  has_open_pr "$n" && continue          # already in flight / awaiting human merge

  ready=1
  for b in $(blockers_of "$n"); do
    is_closed "$b" || { ready=0; break; }
  done
  [ "$ready" -eq 1 ] || continue

  # Flag DB/migration work so the loop can route it through migration-guard.
  # NOTE: this is a prose keyword scan and can false-positive on issues that
  # merely mention migrations. Tighten to a label (e.g. "area:db-migration") or
  # to detecting an actual V__.sql in scope if the false positives bite.
  mig=false
  if gh issue view "$n" --repo "$REPO" --json labels \
       -q '.labels[].name' | grep -q 'area:db-migration'; then
    mig=true
  fi

  echo "<next issue=$n migration=$mig/>"
  exit 0
done

# Candidates exist, but all are blocked or already have an open PR.
echo "<blocked/>"
