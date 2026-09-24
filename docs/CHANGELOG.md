# Changelog

Newest first. One entry per milestone. History only, never instructions.

## M1 — Import and identity (2026-09-24)

- **Schema (V2):** household + user (seeded), account (IBAN only as 32-byte HMAC + masked form), statement_file
  (unique per account + sha256), immutable raw_row (trigger), transaction (unique per account + identity_key,
  bigint minor units), transaction_source.
- **Parsing:** `StatementParser` SPI and detector (threshold, no guessing on ties, override by id); configurable CSV
  parser driven by YAML profiles (`docs/parsers.md`): encodings, delimiters, date formats, `1.234,56`, signed or
  debit/credit amounts, IBAN from preamble or column. Exact money parsing; mod-97 IBAN validation.
- **Identity:** frozen `key_v1` hash normalization; `ref:` or content keys with occurrence index; upsert with every
  raw row linked to one transaction; file-level sha256 no-op; one DB transaction per file.
- **Upload:** `POST /api/imports` (several files, per-file outcomes, per-account summary); account detection by IBAN;
  `GET/POST /api/accounts`. HMAC key from env or generated into the data dir.
- **Tests:** golden files (synthetic generic + Romanian-style Windows-1250); overlap/re-upload/identical-rows
  acceptance; 12-seed property test over random splits, shuffles and import orders; 137 backend tests.

## M0 — Scaffold (2026-09-23)

- **API:** Java 21 / Spring Boot 3.5 / Maven wrapper; `/api/health` with DB round-trip and schema version;
  Flyway V1 baseline; springdoc OpenAPI at `/api/openapi.json`, Swagger UI at `/api/docs`.
- **Tests:** Testcontainers Postgres 16 smoke tests; `OpenApiContractTest` guards the committed `api/openapi.json`.
- **Web:** React 19 + Vite + TypeScript + Tailwind v4; react-router (hash routing); `openapi-fetch` client typed
  from the committed spec (`npm run gen:api`); placeholder home showing system status.
- **Demo mode:** static build backed by typed synthetic fixtures, deployed to GitHub Pages.
- **Ops:** Docker Compose full stack (postgres, api, web via nginx on :3000); GitHub Actions CI (api verify,
  web typecheck/build, compose smoke) and Pages deploy.
- **Docs:** `CLAUDE.md`, `docs/STATUS.md`, `docs/CHANGELOG.md`, `docs/DESIGN.md`, `docs/IMPLEMENTATION_PLAN.md`.
