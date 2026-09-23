# Outflow — Implementation plan for a Claude agent

Sep 23, 2026 · @Costin Fl

## How to use this plan

Paste the kickoff prompt into Claude Code in the `outflow` repo; the agent builds milestone by milestone, stopping at each checkpoint for your review.

1. Create the empty GitHub repo `outflow` and clone it; open it in IntelliJ.
2. Export the design doc (Bank Statement Analyzer — Design) as Markdown and save it as `docs/DESIGN.md`; export this plan as `docs/IMPLEMENTATION_PLAN.md`. The agent treats both as the specification.
3. Put 1–3 real statement exports, anonymized, in `samples/` (see Open decisions). Without them the agent builds against a synthetic generic CSV.
4. Run Claude Code in the IntelliJ terminal and paste the **Kickoff prompt**.
5. At every checkpoint: review the diff, run the app, reply "continue" or give corrections.
6. New session later → paste the **Resume prompt**; the agent reads `docs/STATUS.md` and picks up where it stopped.

Order of work: M0 scaffold → M1 import and identity → M2 merchants and categories → M3 home screen → M4 recurring and review inbox → M5 multi-account. Each milestone ends in a working, demoable app.

## Stack and repo layout

A Spring Boot API with Postgres and a React SPA in one monorepo, runnable locally with a single `docker compose up`.

| Layer | Choice | Why |
| --- | --- | --- |
| Backend | Java 21, Spring Boot 3.x, Maven | Your main stack; records and pattern matching suit the parsers |
| Persistence | Postgres 16, Spring Data JDBC, Flyway migrations | Explicit SQL fits the interval math and upserts; no JPA magic around identity keys |
| Tests | JUnit 5, AssertJ, Testcontainers (Postgres) | Identity and dedup must be tested against the real DB |
| Frontend | React 19 + Vite + TypeScript, Tailwind v4 | Same stack as Wayfork and Meridian |
| API contract | REST + OpenAPI via springdoc; TS client generated from it | One source of truth for DTOs |
| Local run | Docker Compose: postgres, api, web | Self-hosted single user first |

```
outflow/
├── CLAUDE.md
├── docs/
│   ├── DESIGN.md        # the spec (exported)
│   ├── STATUS.md        # resume entrypoint
│   └── CHANGELOG.md
├── samples/             # anonymized statements, golden files
├── api/                 # Spring Boot
│   └── src/main/java/dev/costinfl/outflow/
│       ├── ingest/      # upload, parsers, raw rows, identity
│       ├── txn/         # transactions, transfers, soft match
│       ├── merchant/    # normalization, aliases
│       ├── category/    # rules, resolver
│       ├── recurring/   # detection, subscriptions
│       ├── review/      # review inbox
│       └── insight/     # home summary queries
├── web/                 # React SPA
└── docker-compose.yml
```

Packages are feature slices, not layers; each owns its tables, service and controller.

## Steering files

Four files keep every session oriented; the agent creates the first three in M0 and keeps them current, and never lets them drift from the code.

| File | Holds | Updated |
| --- | --- | --- |
| `CLAUDE.md` | Stack, commands (build, test, run, migrate), conventions, the guardrails below, pointer to STATUS. Short and stable — auto-loaded every session | Only when a convention or command changes |
| `docs/STATUS.md` | Current milestone, last completed checkpoint, next step, open questions, known issues. The only file a resuming session must read first | At every checkpoint |
| `docs/CHANGELOG.md` | What changed per milestone, newest first. History only, never instructions | At the end of each milestone |
| `docs/DESIGN.md` | The specification. Read-only for the agent; proposed changes go to STATUS under "Spec questions" | By you |

**`CLAUDE.md` must contain these commands, verified working:**

```
./mvnw -pl api verify          # all backend tests incl. Testcontainers
./mvnw -pl api spring-boot:run # API on :8080
npm --prefix web run dev       # SPA on :5173
npm --prefix web run typecheck
npm --prefix web run gen:api   # regenerate TS client from OpenAPI
docker compose up              # full stack
```

## Milestones and checkpoints

Six milestones, each split into checkpoints (CP); the agent stops at every CP, updates STATUS, and waits for "continue".

**M0 — Scaffold**

- CP0.1 Maven parent + `api` module, Spring Boot app, health endpoint, Flyway baseline, Testcontainers smoke test.
- CP0.2 `web` Vite + React + TS + Tailwind, placeholder home route, OpenAPI client generation wired.
- CP0.3 Docker Compose, `CLAUDE.md`, `STATUS.md`, `CHANGELOG.md`, GitHub Actions CI running `verify` + `typecheck`.
- Accept: `docker compose up` shows the placeholder page calling `/api/health`; CI green.

**M1 — Import and identity** (DESIGN: Pipeline, Data model, Identity, Parsing)

- CP1.1 Schema: household (seeded single), app\_user, account, statement\_file, raw\_row, transaction, transaction\_source.
- CP1.2 `StatementParser` SPI, detector, one parser: configurable generic CSV (column mapping in YAML) plus the sample bank if provided.
- CP1.3 Normalization for hashing (versioned `key_v1`), identity key, occurrence index, upsert. File-level sha256 no-op.
- CP1.4 Upload endpoint (multipart, several files, one DB transaction per file) and import summary response: new, skipped, per account.
- Accept: tests prove re-upload of the same file → 0 new; overlapping Jan–Mar then Feb–Apr → exact union; two identical same-day rows → 2 transactions, stable across re-imports.

**M2 — Merchants and categories** (DESIGN: Merchant normalization and classification)

- CP2.1 Merchant normalizer as a chain of unit-tested steps; alias table; `merchant` rows.
- CP2.2 Category tree seeded; resolver tiers 1, 2, 4 (user rule, learned, keyword). MCC tier if the sample carries it. No LLM.
- CP2.3 Endpoints: recategorize one transaction, with "apply to merchant" creating a rule; debug endpoint raw → merchant key.
- Accept: on the samples, ≥ 80% of spend categorized; a user rule never gets overwritten by a re-run.

**M3 — Home screen** (DESIGN: Home screen, Detail screens; mockup)

- CP3.1 Insight queries: month total, 3-month average, income, net, top-5 categories + Other with delta vs. usual, accuracy %.
- CP3.2 SPA home screen matching the mockup: month switcher, four blocks, bars, drill-through links. Mobile-first at 390 px.
- CP3.3 Transactions screen with pre-filtered chips; category detail with trend and top merchants.
- CP3.4 Upload flow and import summary screen (First-run flow).
- Accept: with the samples loaded, the home screen answers the three questions from DESIGN Overview; every number drills to its transactions and sums correctly.

**M4 — Recurring and review inbox** (DESIGN: Recurrence detection, Candidate lifecycle, Review inbox)

- CP4.1 Grouping + amount bands; monthly and yearly cadence fit with anchor-day matching; scoring.
- CP4.2 `subscription` + `subscription_rejection`; states PROPOSED → CONFIRMED / REJECTED / ENDED.
- CP4.3 Review inbox API and screen: subscription suggestions, uncategorized merchants, sorted by RON affected.
- CP4.4 Home "Committed every month" block and Recurring screen; coverage nudge when history is under 3 months.
- Accept: seeded fixtures with known subscriptions (fixed, variable, month-end, one missed month) are detected; rejected items never return.

**M5 — Multi-account and tracking** (DESIGN: Internal transfers, Pending vs posted, Prediction and alerts)

- CP5.1 Transfer pairing incl. counterparty IBAN and one-sided provisional marking; excluded from spend.
- CP5.2 Pending/posted soft match + review card.
- CP5.3 Weekly and daily cadences; price-change and missed-charge cards.
- CP5.4 Accounts filter on home.
- Accept: a monthly savings transfer is never counted as spending nor proposed as a subscription.

Later, not in this plan: LLM classification, household and settlement, PDF parsing.

## Kickoff prompt

Paste this as the first message in Claude Code, from the repo root.

```text
You are implementing Outflow, a personal finance app that answers one question:
"where does my money go?" Users upload bank statements from several accounts;
Outflow imports every transaction exactly once, classifies it, and detects
recurring payments for the user to confirm.

SPECIFICATION
- docs/DESIGN.md is the spec. Read it fully before writing code.
- docs/IMPLEMENTATION_PLAN.md (this plan) defines stack, layout, milestones
  and checkpoints. Follow it in order: M0 → M5.
- samples/ holds anonymized real statements. Treat them as golden files.
  If samples/ is empty, build the configurable generic CSV parser and a
  synthetic sample, and note this in STATUS under "Open questions".

WAY OF WORKING
1. Work one checkpoint (CP) at a time. At the end of each CP:
   - all tests green: ./mvnw -pl api verify and npm --prefix web run typecheck
   - update docs/STATUS.md: milestone, CP done, next CP, open questions,
     known issues, spec questions
   - commit with message "CPx.y: <summary>" on branch milestone/Mx
   - STOP and summarize in 5 lines max what you built and how to see it.
     Wait for me to reply "continue" or give corrections.
2. At the end of a milestone, add an entry to docs/CHANGELOG.md and open a
   PR from milestone/Mx to main. Do not merge it.
3. Before starting a CP, restate its acceptance criteria and the tests that
   will prove them. Write those tests first where practical.

NON-NEGOTIABLES
- Identity: identity_key is content-derived and file-independent, with an
  occurrence index per (account, booking date, content hash). Re-uploading
  the same or overlapping data never creates duplicates. Prove it in tests.
- Raw rows are immutable. Derived data must be recomputable from them.
- Amounts are long minor units + ISO currency. Never double or float.
- A user-set category or user decision is never overwritten by automation.
- IBANs are stored only as HMAC hash + masked form.
- No transaction data, amounts or descriptions leave the machine: no LLM
  calls, no telemetry, no external APIs in this plan.
- docs/DESIGN.md is read-only for you. If the spec is ambiguous or wrong,
  choose the conservative option, record it under "Spec questions" in
  STATUS, and mention it at the next stop.

START
Begin with M0. First create CLAUDE.md, docs/STATUS.md and docs/CHANGELOG.md
as described in the plan, then do CP0.1 and stop.
```

Save this plan itself as `docs/IMPLEMENTATION_PLAN.md` (export as Markdown) so the prompt's reference resolves.

## Resume prompt

Paste this at the start of any later session; it relies on `CLAUDE.md` being auto-loaded.

```text
Resume work on Outflow.
1. Read docs/STATUS.md. It is the only source of truth for where we are.
2. Run the test commands from CLAUDE.md and report whether they are green.
   If not, fixing them is the first task; stop and tell me before anything else.
3. Check git status and the current branch against STATUS. Report any mismatch
   instead of guessing.
4. Restate the next checkpoint and its acceptance criteria from
   docs/IMPLEMENTATION_PLAN.md, then implement it.
5. Same stop rules as always: update STATUS, commit "CPx.y: ...", stop,
   summarize in 5 lines, wait for "continue".
```

**Correction prompt** — use when a checkpoint came back wrong:

```text
CPx.y needs changes before we continue:
- <what is wrong, observed behaviour>
- <what you expect instead>
Fix only this, add a test that would have caught it, update STATUS
("Known issues" → resolved), commit "CPx.y fix: ...", and stop.
```

## Guardrails and definition of done

A checkpoint is done only when its acceptance criteria are proven by automated tests and the steering files match the code.

**Definition of done, per checkpoint**

- [ ] Acceptance criteria restated at start, each covered by at least one test
- [ ] `verify` and `typecheck` green locally
- [ ] New tables only via a new Flyway migration; existing migrations never edited
- [ ] STATUS updated; commands in CLAUDE.md still work
- [ ] Commit on `milestone/Mx`, message `CPx.y: …`

**Testing focus**

- Identity and dedup: property-style tests — shuffle rows, split files at random dates, re-import in any order; the transaction set must be identical.
- Parsers: one golden file per parser; assert row count, total sum and identity keys.
- Recurrence: synthetic histories with known answers, including month-end anchors, weekend shifts, a missed month, a price change, and a variable bill.
- Insight queries: sums on home must equal the sum of drill-through transactions.

**The agent must not**

- merge to `main`, force-push, or rewrite history
- add dependencies beyond the stack table without asking at a stop
- call external services or send transaction data anywhere
- commit real statements outside `samples/`, or any unanonymized data
- skip a stop to "save time", or start the next milestone unasked
- weaken or delete a failing test to make the suite green

## Open decisions

The plan runs with the defaults below; change any of them before pasting the kickoff prompt.

| Decision | Default in this plan | Needed by |
| --- | --- | --- |
| First bank and export format | Generic configurable CSV + synthetic sample | M1 (CP1.2) |
| Build tool | Maven (wrapper) | M0 |
| Base package | `dev.costinfl.outflow` | M0 |
| Data access | Spring Data JDBC, no JPA | M1 |
| Deployment | Local Docker Compose only | Before any cloud work |
| UI language | English UI, RON default currency, amounts formatted per browser locale | M3 |
| Auth | None — single local user, seeded household | Before household phase |

- [ ] Put anonymized samples in `samples/`: replace names and IBANs, keep merchant strings and amounts as they are — the normalizer needs the real mess.
- [ ] Confirm the defaults above, or edit this table.
