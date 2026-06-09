#!/usr/bin/env bash
#
# Hexagonal Architecture dependency-rule guard (ADR 0005 rule, ADR 0006 packages).
#
# Dependencies point inward: the domain core depends on nothing; adapters depend
# on the core through ports. This script fails if anything in the inner hexagon
# imports a forbidden concrete dependency. Two tiers are enforced:
#
#   1. com.locarya.domain.models  (strictly pure — NOT even F[_])
#      Forbids doobie, org.http4s, and ANY cats.effect.* import. The domain
#      model layer is plain Scala: entities, value objects, ADTs, pure logic.
#
#   2. com.locarya.domain.ports / com.locarya.domain.services  (abstract F[_] only)
#      Forbids doobie, org.http4s, and the concrete cats.effect.IO (incl. the
#      wildcard `cats.effect._`, which pulls IO into scope). Abstract effect
#      constraints are allowed: `def f[F[_]: Async]` with `import cats.effect.Async`
#      is fine; only the concrete `IO` is forbidden.
#
# Run locally:  ./scripts/check-architecture.sh
# Exits 0 if clean, 1 if any violation is found.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Tier 1 — strictly pure: no doobie/http4s and no effects at all.
PURE_DIRS=(
  "src/main/scala/com/locarya/domain/models"
)
PURE_FORBIDDEN=(
  'import[[:space:]].*\bdoobie'
  'import[[:space:]].*\borg\.http4s'
  'import[[:space:]].*\bcats\.effect\b'
)

# Tier 2 — abstract F[_] allowed, concrete effect/adapters forbidden.
PORT_DIRS=(
  "src/main/scala/com/locarya/domain/ports"
  "src/main/scala/com/locarya/domain/services"
)
PORT_FORBIDDEN=(
  'import[[:space:]].*\bdoobie'
  'import[[:space:]].*\borg\.http4s'
  'import[[:space:]].*\bcats\.effect\.IO\b'
  'import[[:space:]].*\bcats\.effect\._'
)

violations=0

# scan <forbidden-patterns-newline-separated> <dir...>
scan() {
  local patterns="$1"; shift
  local dir abs file pattern matches line
  for dir in "$@"; do
    abs="$ROOT/$dir"
    [ -d "$abs" ] || continue
    while IFS= read -r -d '' file; do
      while IFS= read -r pattern; do
        [ -n "$pattern" ] || continue
        if matches=$(grep -nE "$pattern" "$file"); then
          while IFS= read -r line; do
            echo "VIOLATION: ${file#"$ROOT"/}:$line"
            violations=$((violations + 1))
          done <<< "$matches"
        fi
      done <<< "$patterns"
    done < <(find "$abs" -name '*.scala' -type f -print0)
  done
}

scan "$(printf '%s\n' "${PURE_FORBIDDEN[@]}")" "${PURE_DIRS[@]}"
scan "$(printf '%s\n' "${PORT_FORBIDDEN[@]}")" "${PORT_DIRS[@]}"

echo
if [ "$violations" -gt 0 ]; then
  echo "✗ Hexagonal dependency rule violated: $violations forbidden import(s)."
  echo "  The domain core must not import concrete adapters or effects."
  echo "  See docs/adr/0005-hexagonal-architecture.md (rule) and docs/adr/0006-hexagonal-package-layout.md (packages)."
  exit 1
fi

echo "✓ Hexagonal dependency rule holds — no forbidden imports in domain.models / domain.ports / domain.services."
