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
- `web/` — React 19, Vite, TypeScript, Tailwind v4, react-router (hash routing, for GitHub Pages). TS client (`openapi-fetch`) typed from `api/openapi.json` via `openapi-typescript`.
- Base package `dev.costinfl.outflow`. Packages are feature slices (`ingest`, `txn`, `merchant`,
  `category`, `recurring`, `review`, `insight`, `system`); each owns its tables, service and controller.

## Commands

```
./mvnw -pl api verify              # all backend tests incl. Testcontainers (needs Docker)
./mvnw -pl api spring-boot:run     # API on :8080 (needs Postgres: `docker compose up -d postgres`)
./mvnw -pl api spring-boot:test-run  # API on :8080 with a throwaway Testcontainers Postgres
npm --prefix web ci                # install web dependencies
npm --prefix web run dev           # SPA on :5173, proxies /api to :8080
npm --prefix web run typecheck
npm --prefix web test              # web unit tests (Node test runner, no deps), incl. anonymizer parity
npm --prefix web run build:demo    # static GitHub Pages build (synthetic fixtures, base /outflow/)
./mvnw -pl api verify -Dopenapi.update=true  # refresh api/openapi.json after changing endpoints/DTOs
npm --prefix web run gen:api       # regenerate web/src/api/schema.gen.ts from api/openapi.json
docker compose up --build          # full stack: SPA http://localhost:3000 (proxies /api), API :8080, Postgres :5432
```

API endpoints: `/api/health`, `POST /api/imports` (multipart `files`), `GET/POST /api/accounts`, `PATCH /api/accounts/{id}`,
`GET /api/categories`, `PUT/DELETE /api/transactions/{id}/category`, `GET /api/merchants`, `GET /api/merchants/explain`,
`POST /api/merchants/aliases`, `GET /api/insights/month`, `GET /api/insights/categories/{id}`,
`GET /api/transactions`, `GET /api/review`, `POST /api/review/skip`, `POST /api/review/merchants/{id}/category`,
`POST /api/review/duplicates/{id}`,
`GET /api/subscriptions`, `PATCH /api/subscriptions/{id}`,
`POST /api/subscriptions/{id}/confirm|reject|end`; OpenAPI JSON at `/api/openapi.json`, Swagger UI at `/api/docs`.
Parsers: one YAML profile per CSV format in `api/src/main/resources/parsers/` (keys: `docs/parsers.md`).
Golden files in `samples/`, byte-exact (`.gitattributes`); expected values in `samples/synthetic/README.md`.
Real exports only via the anonymizer (`#/anonymize` in the app, or `java tools/Anonymize.java`; `docs/anonymize.md`);
`SamplesGuardTest` blocks leftover PII. The two implementations must stay byte-identical: change both, regenerate
`web/test/anonymize/expected-*` with the Java tool, and both test suites check them.

API contract: `api/openapi.json` is committed; `OpenApiContractTest` fails when it drifts from the live API.
After an API change: refresh the spec, run `gen:api`, commit both.

Demo mode (GitHub Pages): `VITE_DEMO=true` swaps the client's fetch for `web/src/demo/demoFetch.ts`, which answers
from `web/src/demo/fixtures.ts`. Fixtures are typed so every GET endpoint needs one; synthetic data only, ever.

CI (`.github/workflows/ci.yml`): api `verify`; web gen:api-is-current + typecheck + builds; compose smoke test.
Pages (`.github/workflows/pages.yml`): demo build deployed on push to `main` and the current dev branch.

DB connection: env `OUTFLOW_DB_URL`, `OUTFLOW_DB_USER`, `OUTFLOW_DB_PASSWORD` (defaults: local `outflow`/`outflow`).
IBAN HMAC key: `OUTFLOW_IBAN_HMAC_KEY` (base64, ≥ 32 bytes) or generated once into `$OUTFLOW_DATA_DIR/iban-hmac.key`
(default `./data`, gitignored). Tests use a fixed key from `api/src/test/resources/config/application.yml`.
Pipeline per upload (one DB transaction): parse → import → `UploadService.derive()`: `MerchantService.assignMissing` →
`SoftMatchService.matchAll` (pending rows superseded by their posted version) → `CategoryService.categorizeAll` →
`TransferService.pairAll` (recomputes own-account transfer pairs) → `SubscriptionService.refreshNow` (also after
category or alias changes). Superseded pending rows count nowhere (`Scope.MONTH` excludes them). Transactions with `category_source = 'USER'` are never recomputed.
Home-screen numbers: every figure is a sum over a `txn.Scope` predicate; the transaction list uses the same
predicates, so figures always equal their drill-through. Never compute a figure outside `Scope`.
Count tests from `api/target/surefire-reports/TEST-*.xml`: `@Nested` classes are missing from the text summary.
Tests truncate tables between cases (`ImportFixtures.reset`): TRUNCATE is the only way past the raw_row trigger.

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
