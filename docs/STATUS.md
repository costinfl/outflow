# Status

_Resume entrypoint. Updated at every checkpoint._

| | |
| --- | --- |
| Milestone | **M0 — Scaffold: complete** (PR to `main` pending — see Open question 6) |
| Last completed | **CP0.3** — Docker Compose full stack, CI, GitHub Pages demo deploy, hash routing |
| Next | **M1 / CP1.1** — schema: household (seeded single), app_user, account, statement_file, raw_row, transaction, transaction_source |
| Branch | `claude/outflow-project-setup-vbwx3f` (see Open questions) |

## CP0.3 — done

- `docker compose up --build` → SPA on http://localhost:3000 shows the placeholder calling `/api/health` through
  nginx → api → postgres. Verified locally in a browser; CI job `compose` proves it on every push.
- CI (`ci.yml`): `api — verify`, `web — typecheck + build` (also fails if `schema.gen.ts` is stale vs
  `api/openapi.json`), `docker compose — full stack smoke`.
- Pages (`pages.yml`): demo build deployed on push to `main` and `claude/outflow-project-setup-vbwx3f`.
- react-router v7 with `HashRouter`; home route + not-found route.

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
   to `main` and the dev branch (allowed in the github-pages environment). Synthetic fixtures only.
5. ~~Client-side routing~~ — approved: react-router with hash routing, added in CP0.3.
6. **No `main` branch exists yet**, so the M0 PR has no base. Proposal: you create `main` (e.g. from an empty
   initial commit, or let me push one with your OK), then I open the PR `claude/outflow-project-setup-vbwx3f → main`.

## Known issues

- Dev-container only: Docker Hub rate-limits image pulls here (429); images were pulled via `mirror.gcr.io`.
  Not a project issue; CI and local machines pull normally.
- Dev-container only: `docker build` needs the sandbox proxy + CA injected, so compose images were verified with
  sandbox-only Dockerfile copies (committed Dockerfiles unchanged). The CI `compose` job builds the real ones.

## Spec questions

1. DESIGN says file storage on "GCS or local disk"; the plan is local-only. Conservative choice: local disk only.
2. Plan CP3.2 says "matching the mockup", but there is no mockup in DESIGN or the repo. Needed before M3, else
   the Home screen section of DESIGN is the reference.
3. DESIGN open question "Frontend stack" is settled by the plan: React SPA.
