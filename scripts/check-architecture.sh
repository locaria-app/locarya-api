#!/usr/bin/env bash
#
# Hexagonal Architecture dependency-rule guard (ADR 0005).
#
# The domain core and application core must stay independent of concrete
# delivery/persistence/effect types. This script fails if anything under
#   - com.locarya.core.domain  (pure: no F[_], no IO)
#   - com.locarya.services     (abstract F[_] only)
# imports a forbidden concrete dependency:
#   - doobie            (persistence adapter)
#   - org.http4s        (transport adapter)
#   - cats.effect.IO    (concrete effect; abstract typeclasses like Async are fine)
#   - cats.effect._     (wildcard pulls IO into scope — import specific typeclasses instead)
#
# Abstract effect constraints are allowed: `def f[F[_]: Async]` with
# `import cats.effect.Async` is fine; only the concrete `IO` is forbidden.
#
# Run locally:  ./scripts/check-architecture.sh
# Exits 0 if clean, 1 if any violation is found.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Guarded source roots (inner layers of the hexagon).
GUARDED_DIRS=(
  "src/main/scala/com/locarya/core/domain"
  "src/main/scala/com/locarya/services"
)

# Forbidden import patterns (matched on `import` lines only, as extended regex).
FORBIDDEN=(
  'import[[:space:]].*\bdoobie'
  'import[[:space:]].*\borg\.http4s'
  'import[[:space:]].*\bcats\.effect\.IO\b'
  'import[[:space:]].*\bcats\.effect\._'
)

violations=0

for dir in "${GUARDED_DIRS[@]}"; do
  abs="$ROOT/$dir"
  [ -d "$abs" ] || continue

  # All Scala sources under this guarded root.
  while IFS= read -r -d '' file; do
    for pattern in "${FORBIDDEN[@]}"; do
      if matches=$(grep -nE "$pattern" "$file"); then
        while IFS= read -r line; do
          echo "VIOLATION: ${file#"$ROOT"/}:$line"
          violations=$((violations + 1))
        done <<< "$matches"
      fi
    done
  done < <(find "$abs" -name '*.scala' -type f -print0)
done

echo
if [ "$violations" -gt 0 ]; then
  echo "✗ Hexagonal dependency rule violated: $violations forbidden import(s)."
  echo "  The domain/application core must not import concrete adapters or effects."
  echo "  See docs/adr/0005-hexagonal-architecture.md."
  exit 1
fi

echo "✓ Hexagonal dependency rule holds — no forbidden imports in core.domain / services."
