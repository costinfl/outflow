# Status

_Resume entrypoint. Updated at every checkpoint._

| | |
| --- | --- |
| Milestone | **M2 — Merchants and categories** (M1 merged to `main` via PR #2) |
| Last completed | **CP2.2** — category tree, resolver tiers 1/2/4, recategorization that never overrides the user |
| Next | **CP2.3** — endpoints: recategorize one transaction (+ "apply to merchant" rule); debug raw → merchant key |
| Branch | `claude/outflow-project-setup-vbwx3f` (`main` + M2 work) |

## CP2.2 — done

Package `category`. Migration V4. 218 backend tests green.

- 18 seeded categories (DESIGN list) with a `kind`: SPEND, INCOME (Income), TRANSFER (Transfer) → `CategoryServiceTest`
- `CategoryResolver` (pure): tier 1 USER rules (1.0) → tier 2 learned `merchant.default_category_id` (0.95) →
  tier 4 SEED keyword rules on whole words of the merchant key (0.70) → uncategorized. Within a tier: priority, then
  MERCHANT before KEYWORD, then the longer pattern → `CategoryResolverTest`
- `transaction.category_id / category_source / category_confidence`; `category_source = 'USER'` is never recomputed
- `CategoryService.categorizeAll()`: runs after merchant assignment on every upload (same DB transaction);
  recomputes everything else from rules, writes only changes
- M2 acceptance: ≥ 80% of spend categorized on the samples (it is 100%, see Open question 2); a manual category
  survives re-runs even against a user rule and a learned category; tiers unwind cleanly when rules are removed

## CP2.1 — done

Package `merchant` (`normalize/` holds the steps). Migration V3. 206 backend tests green.

- `MerchantNormalizer` = ordered steps, each a pure function with its own tests (`MerchantStepsTest`):
  `BasicCleanup` → `ChannelPrefix` → `WebAddress` → `VolatileTokens` → `FillerWords` → `TrailingLocation` → `AliasStep`
- Golden raw → key table incl. real-world shapes (PayPal, Amazon, eMAG, OMV, Glovo) → `MerchantNormalizerTest`
- One merchant, one key: every row of the samples maps to exactly 11 keys (rent across month names, Spotify plan
  codes, Netflix refs, Starbucks locations, Romanian-style diacritics all collapse) → `samplesCollapseToOneKeyPerMerchant`
- `merchant` + `merchant_alias` tables (EXACT / PREFIX, SEED / USER; 45 seeded chain aliases), `transaction.merchant_id`
- `MerchantService`: uploads assign merchants in the same DB transaction; `reassignAll()` recomputes from raw
  descriptions and writes only changes; a user alias moves exactly the matching transactions → `MerchantServiceTest`

## CP1.4 — done

137 backend tests green; web typecheck and both builds green.

- `POST /api/imports` (multipart `files`, optional `accountId`, `parserId`): each file in its own DB transaction;
  per-file outcome `IMPORTED | DUPLICATE_FILE | NEEDS_PARSER (candidates with reasons) | NEEDS_ACCOUNT | FAILED
  (message)`; per-account and total new/skipped counts with periods → `ImportControllerTest` (10 tests, real HTTP)
- Accounts detected from the file's IBAN: HMAC-SHA256 lookup, created on first sight as "Account ••0000" with only
  hash + masked form stored; a file whose IBAN contradicts the chosen account is refused
- `GET /api/accounts`, `POST /api/accounts` (for IBAN-less statements); OpenAPI spec, TS client and demo fixtures updated
- HMAC key: `OUTFLOW_IBAN_HMAC_KEY` or generated once into `<OUTFLOW_DATA_DIR>/iban-hmac.key` (0600), kept
  outside the DB → `IbanKeyConfigTest`. Docker: `apidata` volume at `/data`
- Manually verified with the real app: first run (IBAN file → account created; generic file → NEEDS_ACCOUNT),
  restart then same IBAN → same account (key stable), overlap upload → 84 new / 42 skipped; account sums match the
  golden values; no plain IBAN anywhere in `account`

**M1 acceptance** (plan): re-upload → 0 new; Jan–Mar then Feb–Apr → exact union; two identical same-day rows →
2 transactions, stable across re-imports. All proven at service level (CP1.3, incl. property test) and over HTTP.

## CP1.3 — done

Packages `ingest.identity` and `ingest` (`ImportService`). 124 backend tests green.

- `HashNormalizer` (`key_v1`, frozen): uppercase, diacritics stripped (cedilla and comma-below), whitespace
  collapsed, card masks / auth codes / times / dates dropped → `HashNormalizerTest`
- `IdentityKeys`: `ref:<ref>` when the bank gives one, else
  `key_v1:sha256(account|booking date|amount|currency|norm)#n`, with `n` ordered by (value date, raw description,
  row no). The pinned hashes were computed outside Java → `IdentityKeysTest.keyV1IsFrozen`
- `ImportService.importFile(account, name, bytes, parser)`: one DB transaction. Parse first (a bad file stores
  nothing), sha256 file no-op, immutable raw rows, upsert by (account, identity_key), every raw row linked to
  exactly one transaction → `ImportServiceTest`:
  - same file twice → duplicate, 0 new, nothing stored
  - Jan–Mar then Feb–Apr → exactly the union: 84 transactions, sum 1,243,801 (computed independently); 126 raw rows
  - either import order → identical key set
  - two identical same-day rows → `#1`/`#2`, stable on re-import; a third seen later is new
  - a failure after writes rolls back the whole file
- `IdentityPropertyTest`: 12 seeds × random overlapping day-boundary splits, rows shuffled within files, random
  import order, one file uploaded twice → same 84 keys as a single import. A planted bug (row number in the hash)
  fails 20 of 29 identity tests, so the suite catches key drift.

## CP1.2 — done

Package `ingest.parse`. 80 backend tests green.

- SPI exactly as DESIGN: `StatementParser.id() / detect(FileSample) → DetectionScore / parse(InputStream) → ParsedStatement`
  (account hint + period + rows with raw payload and parsed fields) → `ConfigurableCsvParserTest`
- `StatementDetector`: best score ≥ 0.75 wins; unknown files and ties choose nothing but explain every candidate;
  override by id; duplicate ids rejected → `StatementDetectorTest`
- One `ConfigurableCsvParser` per YAML profile (`classpath:parsers/*.yml`, extensible via
  `outflow.parsers.locations`); profile keys documented in `docs/parsers.md`; unknown keys and invalid settings fail
  at startup → `CsvProfileTest`, `ParserConfigTest`
- Exact money parsing into minor units, strict about separators and currency decimals → `MoneyParserTest`
- IBAN: mod-97 validated, masked `RO49 •••• 0000`; plain value in memory only, never in `toString()` → `IbanTest`
- Golden files in `samples/synthetic/` (generic Jan–Mar and Feb–Apr, Romanian-style Feb in Windows-1250 with
  debit/credit and preamble IBAN); row count, sums and periods match values computed independently → `GoldenFileTest`
- A row that can't be read fails the whole file with its row number; rows are never skipped silently

## CP1.1 — done

Migration `V2__import_schema.sql`. Proven by `ImportSchemaTest` (10 tests, real Postgres):

- one household and one user seeded (single local user, no auth)
- `statement_file` unique on (account_id, sha256): the exact same file twice for one account is rejected
- `transaction` unique on (account_id, identity_key); the same key in another account is allowed
- `raw_row` is immutable: a trigger rejects UPDATE and DELETE; `row_no` is unique per file
- `transaction_source`: several raw rows (from different files) → one transaction; a raw row → at most one transaction
- amounts are `bigint` minor units (signed, negative = out); currency must be an uppercase 3-letter ISO code
- `account` has no plain IBAN column; `iban_hash` must be 32 bytes (HMAC-SHA256); `iban_masked` rejects a full IBAN;
  an account without an IBAN is allowed (detection may fail)

Health tests now derive the expected schema version from the migrations instead of hard-coding `1`.

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

1. ~~`docs/DESIGN.md` missing~~ — resolved in CP0.2.
2. **`samples/` has no real statements.** M1 uses the configurable generic CSV parser + synthetic samples. Add
   anonymized exports of your bank to `samples/<bank>/` and I'll write its profile and golden test. This matters
   most for M2: the ≥ 80% categorized target is measured on the samples. On the synthetic samples it is 100%, which
   proves the mechanism but not the quality: I wrote both the samples and the keyword seeds.
3. **Branch naming.** The plan says `milestone/Mx`; this cloud session is pinned to
   `claude/outflow-project-setup-vbwx3f`. Commits use `CPx.y:`; milestone PRs come from this branch. Merge the M1
   PR before M2 lands, or it will grow to include M2.
4. ~~GitHub Pages~~ — done: demo build deployed from `main` and the dev branch.
5. ~~Client-side routing~~ — done: react-router, hash routing (CP0.3).
6. ~~No `main` branch~~ — resolved: PR #1 merged M0 + CP1.1; `main` was merged back into the dev branch (no rewrite).

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
4. `statement_file.account_id` (DESIGN) assumes one account per file. CAMT.053 files can hold several accounts.
   Conservative choice for now: one account per file; revisit when a multi-account format is added (M5).
5. DESIGN lists `transaction.merchant_id`, `category_id`, `category_source`, `category_confidence`,
   `transfer_pair_id`, `subscription_id`. They are added in the milestones that create the referenced tables
   (M2, M4, M5) so every column has a real foreign key from day one.
6. DESIGN lets the user override parser detection. The detector refuses to guess on a tie or below 0.75; CP1.4
   returns the candidates so the user picks. Conservative choice: no import without a confident or explicit parser.
7. **Disjoint mid-day cuts.** The occurrence index handles a later file that repeats a day partially (DESIGN's edge
   case). It cannot handle two files that each hold a *different* part of the same day with identical rows (file A
   ends with coffee #1, file B starts with coffee #2): both are `#1`, so one coffee is lost. Bank exports are cut at
   day boundaries, so this should not happen in practice. The property test covers day-boundary cuts only. A fix
   would need the bank's reference or a running balance. Flagging it rather than guessing.
8. **Reference vs content keys across formats.** A `ref:` key and a `key_v1:` key never match, so the same account
   imported once as CSV without references and once as a format with references (e.g. CAMT.053) would duplicate.
   Conservative choice: keep DESIGN's priority; revisit when a second format for the same bank arrives (M5).
9. **Counterparty IBANs in descriptions.** DESIGN stores account IBANs only as hash + masked form, but bank
   descriptions also carry *counterparty* IBANs (e.g. "TRANSFER CATRE CONT ECONOMII RO49…"), which end up in
   `raw_row.payload` and `transaction.description_raw` as the bank wrote them. M5 needs them to pair transfers.
   Conservative choice: keep raw data as-is (raw rows are immutable facts); M5 will hash them for matching and the UI
   will mask IBAN-shaped text when displaying descriptions. Say if you want them masked at import instead.
10. **Losing the HMAC key** makes existing accounts unrecognisable by IBAN (uploads would create new accounts). Its
    file lives in the data dir next to the DB volume; back up both. A key-rotation tool is not planned for M1–M5.
11. Merchant keys are interpretations, not identities: unlike `key_v1`, the normalizer may improve and
    `reassignAll()` recomputes every merchant. User rules (CP2.2) will key on merchant *keys*; a normalizer change that
    renames a key would orphan rules on it. Conservative choice: rules store the key, and CP2.3's debug view shows
    raw → key so a changed key is visible; revisit with real samples.

