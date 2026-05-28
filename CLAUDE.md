# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **Locarya API** project - currently in initial setup phase.

## Development Commands

_To be added as the project structure is established._

## Architecture

### Stack

- **Backend:** Scala 3 + Typelevel (Cats Effect 3, http4s, doobie, circe)
- **Payment Gateway:** Asaas (split payment)
- **Pattern:** Tagless Final, ADTs, IO monad

Ver `docs/adr/0002-scala-typelevel-stack.md` para justificativa completa da escolha de stack.

### Domain Language

O domínio está completamente documentado em `CONTEXT.md` no root do repositório. Leia antes de trabalhar no código — define termos canônicos como Locador, Cliente, Reserva, Item, Combo, Pagamento, Monitor, etc.

Decisões arquiteturais importantes estão em `docs/adr/`:
- **ADR #1:** Split payment direto via Asaas (vs custódia na plataforma)
- **ADR #2:** Scala + Typelevel (vs Node.js/Java/Kotlin)
- **ADR #3:** Observability & Structured Logging (log4cats + JSON, correlation tracking)

## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues for this repository. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses default triage label vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repository with CONTEXT.md and docs/adr/ at the root. See `docs/agents/domain.md`.

## Notes

- This is a new repository - architecture decisions and development patterns should be documented here as they are established
- When adding code, update this file with relevant build commands, test procedures, and architectural patterns
