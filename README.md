# Locarya API

Backend API for Locarya - party equipment rental SaaS platform for Brazil.

## Stack

- **Language:** Scala 3.3.1
- **Framework:** Typelevel stack (Cats Effect 3, http4s, doobie, circe)
- **Database:** PostgreSQL 16
- **Migrations:** Flyway
- **Logging:** log4cats + Logback with structured JSON output
- **Payment Gateway:** Asaas (split payment)

## Prerequisites

- JDK 21
- sbt 1.9.8+
- Docker & Docker Compose (for local development)

## Quick Start

### 1. Start PostgreSQL

```bash
docker-compose up -d
```

This starts a PostgreSQL 16 container on `localhost:5432` with:
- Database: `locarya`
- User: `locarya`
- Password: `locarya_dev_password`

### 2. Run Database Migrations

Migrations run automatically on application startup via Flyway.

To run migrations manually:

```bash
sbt flywayMigrate
```

To check migration status:

```bash
sbt flywayInfo
```

### 3. Compile and Test

```bash
# Compile
sbt compile

# Run tests
sbt test

# Run specific test
sbt "testOnly com.locarya.core.domain.MoneySpec"

# Continuous testing
sbt ~test
```

### 4. Run Application

```bash
sbt run
```

API will be available at `http://localhost:8080`

## Project Structure

```
src/
├── main/
│   ├── resources/
│   │   ├── application.conf          # App configuration
│   │   ├── logback.xml              # Logging configuration
│   │   └── db/migration/            # Flyway migrations
│   └── scala/com/locarya/
│       ├── core/                    # Domain models (pure, no effects)
│       ├── services/                # Business logic (algebras + implementations)
│       ├── infrastructure/          # Repositories, external services
│       ├── http/                    # HTTP routes, middleware
│       └── app/                     # Application entry point
└── test/
    └── scala/com/locarya/           # Tests mirror main structure
```

## Development

### Environment Variables

Set these for non-default configuration:

```bash
export DATABASE_URL="jdbc:postgresql://localhost:5432/locarya"
export DATABASE_USER="locarya"
export DATABASE_PASSWORD="locarya_dev_password"
export HTTP_HOST="0.0.0.0"
export HTTP_PORT="8080"
```

### Logging

Logs are output as structured JSON to stdout (via logstash-logback-encoder).

Each HTTP request includes a correlation ID (`X-Correlation-ID` header) for request tracing.

### Database

To connect to the local database:

```bash
docker exec -it locarya-postgres psql -U locarya -d locarya
```

To reset the database:

```bash
docker-compose down -v
docker-compose up -d
```

## Autonomous backlog workflow (Ralph)

Drives `ready-for-agent` GitHub issues through the project's agent stack — one
issue per pull request, which a human merges. The dependency order lives in each
issue's `## Blocked by` section; the loop recomputes what's ready on every pass.

**Prerequisites**
- `claude` CLI authenticated; `gh` CLI authenticated for this repo.
- The `tdd` skill installed at `~/.claude/skills/tdd/`.
- Project agents present in `.claude/agents/` (context-gatherer, migration-guard, pr-runner).
- Issues use the template's `## Acceptance criteria` and `## Blocked by` sections,
  and carry the `ready-for-agent` label when implementable.

**Setup**
```bash
chmod +x scripts/ralph/*.sh
```

**Run**
```bash
# See which issue is next (read-only, deterministic):
./scripts/ralph/next-issue.sh

# Implement the next ready issue and open its PR (watch it):
./scripts/ralph/ralph-once.sh

# Run unattended for up to N iterations (default 15):
./scripts/ralph/afk-ralph.sh 10
```

**What a single pass does**
1. `context-gatherer` builds an implementation brief for the issue.
2. `migration-guard` reviews DB changes (only when the issue touches the schema).
3. `/tdd` implements it red-green-refactor.
4. `pr-runner` opens a PR (`Closes #N`) on branch `issue-<N>-<slug>` — never merges.

**An issue is "ready" when** it is open, labeled `ready-for-agent`, not labeled
`blocked`/`needs-design`, has a `## Acceptance criteria` section, has no open PR,
and every `#N` under its `## Blocked by` is closed.

**Human gate**: PRs are never auto-merged. Merge a PR (which closes its issue) to
unblock the next ones, then run again. `ralph-once.sh` resets to a clean `main`
at the start, so serial runs don't stack work across branches.

## Documentation

- **Domain Language:** See `CONTEXT.md` for canonical domain terms
- **Architecture Decisions:** See `docs/adr/` for ADRs
- **API Docs:** (Coming soon - OpenAPI spec)

## CI/CD

GitHub Actions runs on all pull requests:
- Compilation check
- Test suite
- (Future: formatting, linting, coverage)

## License

Proprietary - FlexRent/Locarya
