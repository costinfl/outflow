# Status

_Resume entrypoint. Updated at every checkpoint._

| | |
| --- | --- |
| Milestone | **M0 — Scaffold** |
| Last completed | **CP0.2** — `web` Vite + React 19 + TS + Tailwind v4, placeholder home, OpenAPI client generation, Pages demo mode |
| Next | **CP0.3** — Docker Compose full stack, GitHub Actions CI (`verify` + `typecheck`), Pages deploy of the demo build |
| Branch | `claude/outflow-project-setup-vbwx3f` (see Open questions) |

## CP0.2 — done

- `web/` builds and typechecks → `npm --prefix web run typecheck`, `npm --prefix web run build`.
- Placeholder home calls `/api/health` through the generated, typed client → typecheck; manually verified in a
  browser at 390 px against the running API (dev server proxy).
- OpenAPI client generation wired: `api/openapi.json` committed, guarded by `OpenApiContractTest`;
  `npm run gen:api` → `web/src/api/schema.gen.ts`.
- Demo mode: `npm run build:demo` serves `/api/*` from typed synthetic fixtures; fixtures are code-split and absent
  from the normal build (checked in `dist/`). Verified in a browser under `/outflow/`.
- `./mvnw -pl api verify` green (4 tests).

## CP0.1 — done

- `/api/health` with DB round-trip, Flyway baseline on real Postgres 16, OpenAPI served → `HealthControllerTest`.

## Open questions

1. ~~`docs/DESIGN.md` missing~~ — resolved, added in CP0.2.
2. **`samples/` is empty.** Per plan, M1 will build the configurable generic CSV parser + a synthetic sample.
3. **Branch naming.** The plan says `milestone/Mx`; this cloud session is pinned to
   `claude/outflow-project-setup-vbwx3f`. Commits use the `CPx.y:` convention on that branch; the M0 PR will
   come from it.
4. **GitHub Pages (Actions source)** — approved: demo mode built in CP0.2, deploy workflow in CP0.3, on push
   to `main`. Synthetic fixtures only. Note: the default `github-pages` environment only accepts deployments
   from `main`; to preview branches, allow them in Settings → Environments → github-pages.
5. **Client-side routing.** The placeholder is a single page. M3 needs several screens (home, transactions,
   category detail, recurring). Proposal: add `react-router` (not in the stack table, so asking), using hash
   routing, since Pages has no server-side SPA fallback.

## Known issues

- Dev-container only: Docker Hub rate-limits image pulls here (429); images were pulled via `mirror.gcr.io`.
  Not a project issue; CI and local machines pull normally.

## Spec questions

1. DESIGN says file storage on "GCS or local disk"; the plan is local-only. Conservative choice: local disk only.
2. Plan CP3.2 says "matching the mockup", but there is no mockup in DESIGN or the repo. Needed before M3, else
   the Home screen section of DESIGN is the reference.
3. DESIGN open question "Frontend stack" is settled by the plan: React SPA.
