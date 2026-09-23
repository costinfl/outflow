# Outflow

Personal finance app answering "where does my money go?". Users upload bank statements from
several accounts; Outflow imports every transaction exactly once, classifies it, and detects
recurring payments for the user to confirm.

**Start every session by reading `docs/STATUS.md`** — it is the only source of truth for where we are.

- Spec: `docs/DESIGN.md` (read-only for agents; spec questions go to STATUS).
- Plan, milestones, checkpoints: `docs/IMPLEMENTATION_PLAN.md`.
- History: `docs/CHANGELOG.md` (history only, never instructions).

## Stack

- `api/` — Java 21, Spring Boot 3.5, Maven (wrapper), Spring Data JDBC (no JPA), Flyway, Postgres 16.
  Tests: JUnit 5, AssertJ, Testcontainers.
- `web/` — React 19, Vite, TypeScript, Tailwind v4. TS client generated from the API's OpenAPI. *(from CP0.2)*
- Base package `dev.costinfl.outflow`. Packages are feature slices (`ingest`, `txn`, `merchant`,
  `category`, `recurring`, `review`, `insight`, `system`); each owns its tables, service and controller.

## Commands

```
./mvnw -pl api verify              # all backend tests incl. Testcontainers (needs Docker)
./mvnw -pl api spring-boot:run     # API on :8080 (needs Postgres: `docker compose up -d postgres`)
./mvnw -pl api spring-boot:test-run  # API on :8080 with a throwaway Testcontainers Postgres
npm --prefix web run dev           # SPA on :5173                        (from CP0.2)
npm --prefix web run typecheck                                            (from CP0.2)
npm --prefix web run gen:api       # regenerate TS client from OpenAPI   (from CP0.2)
docker compose up                  # full stack (CP0.1: postgres only; api + web from CP0.3)
```

API endpoints: `/api/health`, OpenAPI JSON at `/api/openapi.json`, Swagger UI at `/api/docs`.
DB connection: env `OUTFLOW_DB_URL`, `OUTFLOW_DB_USER`, `OUTFLOW_DB_PASSWORD` (defaults: local `outflow`/`outflow`).

## Way of working

- One checkpoint (CP) at a time. Restate its acceptance criteria and the tests that prove them first.
- End of CP: `verify` + `typecheck` green, STATUS updated, commit `CPx.y: <summary>`, stop and summarize
  in ≤5 lines, wait for "continue".
- End of milestone: CHANGELOG entry, PR to `main`. Never merge it.
- Schema changes only via a new Flyway migration `api/src/main/resources/db/migration/V<n>__*.sql`.
  Never edit an existing migration.

## Non-negotiables

- `identity_key` is content-derived and file-independent, with an occurrence index per
  (account, booking date, content hash). Re-uploading same/overlapping data never duplicates. Tested.
- Raw rows are immutable; derived data must be recomputable from them.
- Amounts are `long` minor units + ISO currency. Never `double`/`float`.
- A user-set category or user decision is never overwritten by automation.
- IBANs stored only as HMAC hash + masked form.
- No transaction data leaves the machine: no LLM calls, no telemetry, no external APIs.
- No dependencies beyond the stack above without asking at a stop.
- Never merge to `main`, force-push, or rewrite history. Never weaken or delete a failing test.
- Real statements only in `samples/`, anonymized. Never commit unanonymized data.
