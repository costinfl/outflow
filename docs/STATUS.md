# Status

_Resume entrypoint. Updated at every checkpoint._

| | |
| --- | --- |
| Milestone | **M4 — Recurring and review inbox** (M3 merged to `main` in PR #4) |
| Last completed | **CP4.1** — recurrence detection: amount bands, monthly and yearly cadence fit, scoring |
| Next | **CP4.2** — `subscription` + `subscription_rejection`; states PROPOSED → CONFIRMED / REJECTED / ENDED |
| Branch | `claude/outflow-project-setup-vbwx3f` (`main` + M4 work) |

## CP4.1 — done

280 backend tests green (16 new); typecheck green. No schema or API change yet: candidates are computed, not stored.

- `recurring.RecurrenceDetector` (pure) follows DESIGN step by step:
  - **Bands:** a merchant's charges are split where the next amount is more than 25% above the previous one.
  - **Monthly:** anchored to the median day of the month, clamped to the month's length. A charge counts as on time
    within ±3 days of the anchor, or of the next business day when the anchor falls on a weekend, and belongs to the
    nearest month's anchor.
  - **Yearly:** anchored to the median date across years, measured around the first charge so it works over New Year;
    ±7 days.
  - A cadence only fits when the lower-median gap is exactly one step. R_interval is the share of one-step gaps with
    both ends on time.
  - **Scoring:** 0.4 / 0.2 / 0.2 / 0.2. Proposed at ≥ 0.65, possible at ≥ 0.45, otherwise dropped.
  - **Amount:** FIXED when the coefficient of variation is ≤ 0.02. Expected amount = median of the last 3,
    tolerance = 2 × MAD. The next expected date is snapped to the anchor.
- `recurring.RecurrenceService.detect(today)`: groups outgoing charges by (account, merchant, currency). Transfers,
  cash withdrawals and refunds are left out.
- **Tests:**
  - `RecurrenceDetectorTest` (hand-computed): fixed monthly with weekend shifts, variable, month-end anchor 31, one
    missed month (R_interval 0.8, confidence 0.92), weekend anchor 5 days late, yearly, yearly across New Year, a plan
    next to one-off purchases, the 25% band edge, irregular spending, too few charges, recency decay, a possible-only
    fit, a fit below the threshold.
  - `RecurrenceServiceTest` on a seeded 7-month ledger (`RecurringFixture`): Netflix fixed, Enel variable, Orange month
    end, World Class with April missed, eMAG plan next to one-offs. Exactly these are found; the savings transfer,
    ATM withdrawals, Lidl and salary are not. Accounts are separate streams.
- Quarterly and bi-weekly (in the DESIGN table but not in the plan's CP4.1) are not fitted yet. They add as
  `Cadence` values with the same anchor logic. Weekly and daily come in CP5.3.

## CP3.4 — done

264 backend tests, 8 web tests green; typecheck and both builds green.

- `#/upload`: drop or pick up to 20 files; they are imported as-is. Only files that need an answer ask a question:
  "Which account is X?" (existing account or "New account…" inline) or "Which bank format is X?" (parser candidates);
  just that file is re-sent. Failed files show their reason; the others are unaffected
- Import summary: new transactions and "N already imported, skipped", per account with masked IBAN, period and a
  "new account" mark; rename detected accounts inline; "See where your money went" → home. Empty home links here
- `PATCH /api/accounts/{id}` (name, kind) → `AccountControllerTest`
- Verified end to end in a browser against the real API: IBAN file → account detected (21 new); generic file → asked,
  answered "New account: Main" (63 new, 84 total); rename persisted; same file again → "imported before, nothing
  changed"; home shows the month. Demo mode explains that uploading needs the real app

**M3 acceptance** (plan): the home screen answers DESIGN's questions 1 (spent vs. usual) and 2 (top categories);
question 3 (committed every month) needs recurrence detection, which is M4 (placeholder block shown). Every number
drills to its transactions and sums correctly: automated (`InsightServiceTest`, `TransactionControllerTest`) and
checked in the browser.

## CP3.3 — done

262 backend tests, 8 web tests green; typecheck and both builds green. Verified in a browser at 390 px, light and dark:
home → category → merchant → transactions, chip removal, search, recategorize; figures equal across screens.

- `GET /api/transactions?month=&scope=SPEND|INCOME|ALL&category=&uncategorized=&merchant=&q=`: rows newest first, count,
  total in the number's own sense (spent positive / income / net). `q` matches merchant or bank text, or an exact
  amount (`18.50`, `18,50`, `1.234,56`); LIKE wildcards are literal
- `GET /api/insights/categories/{id}?month=`: the month, 12-month trend (months without data flagged), average over
  months with data, the month's merchants; income categories count money in, others money out
- `TransactionControllerTest` on the hand-computed ledger (now `LedgerFixture`, shared with `InsightServiceTest`): scopes
  equal the home figures; every home category row drills to its rows (total and count); search; category trend/average
- `#/transactions`: chips for scope / category / uncategorized / merchant / search, each removable; rows grouped by day;
  tap a row → masked bank text, where its category came from, category picker + "apply to all from this merchant"
- `#/categories/:id`: amount, average, 12 monthly columns (selected month accented, others de-emphasized, average line,
  only the selected month labelled, each column opens its month), merchants linking to their transactions
- Demo: fixtures computed from the same ledger (`web/src/demo/ledger.ts`) so demo drill-throughs add up; saving a
  category in the demo explains that it needs the real app. Recurring payments listed first: M4

## Anonymizer: payers and payees (done)

- Both implementations: values of Beneficiar / Ordonator / Plătitor (and payee/payer) fields with 2–5 words, no digits
  and no organisation marker are treated as people and replaced everywhere, in both word orders; organisations are kept
  and listed in the report for review. Lowercase card masks and masks glued to a word get fake digits too.
- New shared golden file `web/test/anonymize/raw-ing-utf8.csv` (invented names, ING layout); the two older golden
  files are byte-for-byte unchanged. 8 web tests, 256 backend tests green.

## ING Bank Romania parser (done)

- `ing-ro-csv-v1` (`ingest.parse.ing.IngRoCsvParser`): multi-line Home'Bank records, page chrome anywhere (also inside
  a record), wrapped detail lines, Romanian month names, `1.234,56`, Debit/Credit, running balance kept in the payload.
  ING's "Referinta" is reused by standing orders, so it is part of the description, never the identity reference.
- `ParsedRow.counterparty` + `transaction.counterparty_raw` (V5): bank parsers name the payee; merchant detection uses it
  before the description. V5 also seeds Round Up / deposit / currency exchange → Transfer, deposit interest → Income.
- Golden test (synthetic ING-format file, numbers from its generator): 193 records, totals, types, period; every
  running balance follows from the previous one; chrome never leaks; a standing order sharing one reference stays 3 rows.
  Measured on the real export before it was purged: 4,026 records parsed, totals and the 21-month balance identity
  matched values computed independently.
- Real-data M2 check: only **13% of spending (excluding transfers) is categorized** by the seed keywords, far below the
  80% target. Biggest gaps: person-to-person transfers (review inbox M4 / transfer pairing M5) and merchants the seeds
  do not know. Normalizer issues seen: payment-processor prefixes (PAYU*, MOBILPAY*, NYX*, MPY*, EP*), brand names with
  digits dropped by the volatile-token step. To tune once the sample is fixed.

## Detour after CP3.2 — anonymizer in the browser (done)

- `#/anonymize` (works on the Pages demo too): pick or drop a file, optional names + seed, report (counts, partial
  originals, leftovers, transfer lines to review), preview, download in the original encoding. No network request,
  nothing stored: verified end to end in Chromium (0 requests while anonymizing).
- `web/src/anonymize/anonymize.ts` is a rule-by-rule port of `tools/Anonymize.java`. Shared golden files in
  `web/test/anonymize/` (Windows-1250 and UTF-8 with BOM); `npm --prefix web test` (Node test runner, no deps) and
  `AnonymizeToolTest.goldenFilesSharedWithTheBrowserVersion` both require exactly those bytes. CI runs both.
- The parity work found two double-replacement bugs in the Java tool (long-reference rule re-replacing fake phone
  numbers and CNPs; "card + 4 digits" re-replacing the first group of a fake card number). Nothing leaked (fakes were
  replaced by fakes), but counts and labels were wrong. Fixed in both.

## CP3.2 — done

245 backend tests green; web typecheck and both builds green. Verified in a browser at 390 px, light and dark,
on the demo build and on the real API with the samples uploaded: no horizontal overflow, no console errors.

- `#/?month=YYYY-MM` home: month switcher (prev / next / pick from months with data)
  - Block 1: spent (hero, links to spend transactions), delta vs. the baseline average in words + arrow, income
    (links to income transactions), net
  - Block 2: top 5 categories as one-hue horizontal bars (dataviz specs: ≤ 24 px, 4 px rounded tip, square at the
    baseline), amount + share + delta vs. usual on every row; uncategorized and the folded rest in neutral gray;
    each row links to its category or transactions
  - Block 3: placeholder until recurring detection (M4) + DESIGN's "upload 3+ months" nudge
  - Block 4: accuracy meter + uncategorized payments (links to them)
- API: `MonthSummary` gained `uncategorizedMinor` / `uncategorizedCount`. Uncategorized money can rank below the top 5,
  so the screen must not look for it there (found while checking the demo screenshot; covered by `InsightServiceTest`)
- Money is formatted from exact decimal strings (`Intl.NumberFormat` with a string, never a float); browser locale
- Design tokens in `web/src/index.css` (reference palette, dark mode with its own steps)
- Demo fixtures are month-aware and follow the hand-checked `InsightServiceTest` ledger, so the demo adds up
- Drill targets `#/transactions?...` and `#/categories/:id?...` exist as placeholders showing the filter; CP3.3 fills them
- The old placeholder moved to `#/status`

## Detour after CP3.1 — statement anonymizer (done)

- `tools/Anonymize.java` (plain Java 21, local only): IBANs → checksum-valid `ANON` fakes, card digits, holder and
  listed names → `PERSON_n`, CNP, emails, phones, 10+ digit references; everything else byte-for-byte; deterministic
  with a private seed; prints a review report. Workflow in `docs/anonymize.md`.
- `AnonymizeToolTest` runs the tool as a user does and proves the output parses to the same rows, dates and amounts
  with no personal data left; `SamplesGuardTest` fails the build on real-looking IBANs/CNPs/emails/card numbers in
  `samples/`. 245 backend tests green.
- Waiting on: an anonymized real export in `samples/<bank>/` → profile + golden test + M2 coverage re-check.

## CP3.1 — done

Packages `insight`, `txn`. 238 backend tests green (count them from the XML reports: `@Nested` tests are missing
from surefire's text summary).

- `GET /api/insights/month?month=YYYY-MM&currency=RON` (default: latest month with data) → `MonthSummary`:
  spent, baseline months (0–3) + average + delta %, income, net, accuracy % (confidence-weighted), categorized %,
  top 5 categories (share, usual, delta, count; uncategorized ranked like a category) + folded rest, available months
- `txn.Scope` holds the SPEND / INCOME / MONTH predicates; `InsightService` and `TransactionQueries.list(filter)`
  both use them, so every figure drills to exactly its transactions
- `InsightServiceTest`: a hand-computed month (refund, transfer, salary, unknown inflow, a category without history,
  .01 rounding) matches every figure; drill-through sums equal the figures; top 5 + rest = spent and net = income −
  spent for every sample month; short history and an empty DB behave
- OpenAPI spec, TS client and demo fixture (the hand-computed month) updated

## CP2.3 — done

228 backend tests green; web typecheck and both builds green.

- `PUT /api/transactions/{id}/category {categoryId, applyToMerchant}`: without apply → this transaction only (`USER`);
  with apply → a tier-1 MERCHANT rule replaces any earlier one, the transaction and every non-USER one of the merchant
  follow it; manual exceptions stay. `DELETE` → back to automatic → `CategoryControllerTest`
- Tier 2 learning: ≥ 2 manual edits of a merchant, all the same category → `merchant.default_category_id`; any
  disagreement unlearns; derived from USER transactions, so recomputable
- M2 acceptance over HTTP: an "apply to merchant" rule survives a re-upload and re-runs (11 → 15 Lidl rows on RULE,
  the earlier exception untouched)
- Debug view: `GET /api/merchants` (count, dominant category, 3 raw samples with IBANs masked),
  `GET /api/merchants/explain?raw=` (each step's output, key, category tier), `POST /api/merchants/aliases` (USER
  alias, recompute merchants then categories) → `MerchantControllerTest`
- `GET /api/categories`; `TransactionView` (merchant name, masked description, category + source + confidence)
- OpenAPI spec, TS client and demo fixtures updated

**M2 acceptance** (plan): ≥ 80% of spend categorized on the samples (100% on synthetic; see Open question 2); a user
rule never gets overwritten by a re-run. Both automated.

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

- **GitHub Pages is disabled** since the repository went private and back to public (deploys fail with "Get Pages
  site failed … Not Found"). The user re-enables it: Settings → Pages → Source: GitHub Actions; then the next push or
  a manual run of "Pages (demo)" publishes.

- The first ING sample contained real names of private people (Beneficiar / Ordonator fields). The repository was made
  private and, at the user's request, the file was purged from the dev branch history (force-push, 2026-09-24; `main`
  never had it). The ING parser's golden test now uses `samples/synthetic/ing-ro-2026-q1.csv`. The anonymizer now
  replaces people in Beneficiar / Ordonator / Plătitor fields (both implementations, byte-identical); next the user
  re-anonymizes the export and a real golden test is added. GitHub may keep unreferenced old commits cached: ask GitHub
  Support to purge them (the repository is public again since 2026-09-24).

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
12. **"Accuracy" before the review inbox exists.** DESIGN: share of the month's spend that is "categorized and
    reviewed". Nothing is reviewed until M4, so a literal reading shows 0%. Chosen: spend weighted by category
    confidence (user/rule 1.0, learned 0.95, keyword 0.7, none 0), which answers "how far to trust the totals" from day
    one. The plain categorized share is returned too. Revisit when the review inbox lands.
13. **Uncategorized money in** is neither income nor spending (it may be a refund or an own-account transfer). It
    lowers nothing on the home screen; M4's review inbox will surface it. Uncategorized money *out* counts as spent
    (conservative: better to over- than under-state spending).
14. **One currency at a time.** Insights take a `currency` parameter (default RON). Transactions in other currencies
    are not converted; there is no FX in this plan.
15. **Baseline = previous 3 months that have data.** With one month of history the average uses that one month;
    `baselineMonths` tells the UI to show the "upload more history" nudge.

