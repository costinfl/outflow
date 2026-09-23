# Status

_Resume entrypoint. Updated at every checkpoint._

| | |
| --- | --- |
| Milestone | **M0 — Scaffold** |
| Last completed | **CP0.1** — Maven parent + `api` module, `/api/health`, Flyway baseline, Testcontainers smoke test |
| Next | **CP0.2** — `web` Vite + React + TS + Tailwind, placeholder home route, OpenAPI client generation |
| Branch | `claude/outflow-project-setup-vbwx3f` (see Open questions) |

## CP0.1 — done

Acceptance criteria and proof:

- API boots and answers `/api/health` with a DB round-trip → `HealthControllerTest.healthReportsUpWithDatabaseRoundTrip`
- Flyway baseline applies on real Postgres 16 → `HealthControllerTest.flywayAppliedBaselineAgainstRealPostgres`
- OpenAPI document is served (input for CP0.2 client generation) → `HealthControllerTest.openApiDocumentExposesHealthEndpoint`
- `./mvnw -pl api verify` green (3 tests). `./mvnw -pl api spring-boot:run` against `docker compose up -d postgres`
  verified manually: `{"status":"UP","database":"UP","schemaVersion":"1"}`.

## Open questions

1. **`docs/DESIGN.md` is missing.** Only the implementation plan was provided. M0 does not need it, but M1
   onward references its sections (Pipeline, Data model, Identity, Parsing…). Needed before CP1.1.
2. **`samples/` is empty.** Per plan, M1 will build the configurable generic CSV parser + a synthetic sample.
3. **Branch naming.** The plan says `milestone/Mx`; this cloud session is pinned to
   `claude/outflow-project-setup-vbwx3f`. Commits use the `CPx.y:` convention on that branch; the M0 PR will
   come from it.
4. **GitHub Pages (Actions source).** Pages is static only — it cannot run the API or Postgres. Proposal for
   CP0.2/CP0.3: build the SPA in a "demo" mode backed by bundled **synthetic** fixtures and deploy that to Pages
   on push to `main`. Never real data. Note: the default `github-pages` environment only accepts deployments
   from `main`; to preview branches, allow them in Settings → Environments → github-pages.

## Known issues

- Dev-container only: Docker Hub rate-limits image pulls here (429); images were pulled via `mirror.gcr.io`.
  Not a project issue; CI and local machines pull normally.

## Spec questions

_None yet (spec not available — see Open question 1)._
