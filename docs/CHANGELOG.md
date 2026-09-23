# Changelog

Newest first. One entry per milestone. History only, never instructions.

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
