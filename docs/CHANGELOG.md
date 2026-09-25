# Changelog

Newest first. One entry per milestone. History only, never instructions.

## After the plan — real data (2026-09-25)

- **Anonymizer:**
  - free-text transfer notes (and their wrapped lines) become `NOTE_n` in both implementations
  - a crash on 10-digit references fixed
  - the real ING sample re-anonymized; the leaky upload was removed from the dev branch history
- **ING parser:** accepts the capitalised "August" of real exports. A golden test runs on the real 21-month export
  (4,026 records, balances checked).
- **Merchant keys:** payment-processor prefixes (PayU*, MobilPay*, NYX*, …) are stripped, and brands with a digit
  are kept.
- **Seeds:** Romanian keywords (V11) and a new Insurance category (V12).
- **Categorization on real data:** merchant spending 40.8% → 72.8%, all spending 13.3% → 22.6%. The rest is
  transfers to people, for the review inbox.
- **Tests:** 364 backend, 10 web.

## M5 — Multi-account and tracking (2026-09-25)

- **Own-account transfers (V8):**
  - Pairs of opposite, equal amounts in two own accounts within 3 business days, greedy by smallest gap.
  - Counterparty IBANs (HMAC-matched to own accounts) confirm a pair or mark a one-sided transfer as provisional.
  - Ties stay unpaired. Pairs are recomputed on every upload, so upload order does not matter.
  - Transfers are never spending or subscriptions; the import summary counts them.
- **Pending vs posted (V9):**
  - Profiles can map a status column.
  - An identical posted row turns a pending one posted.
  - Otherwise a soft match (same merchant, ±5% or 2 units, ≤ 5 days) supersedes the pending row, which is never
    deleted and counts nowhere.
  - Ambiguous matches become "Possible duplicate" review cards.
- **Tracking (V10):**
  - Weekly and daily cadences.
  - Confirmed subscriptions pick up their new charges.
  - Price-change and missed-charge review cards; two missed charges end a subscription until charges return.
  - Status chips on the Recurring screen.
- **Accounts filter on home:** every figure, drill-through, category page and the Recurring screen follow it, and
  figures still equal their lists.
- **Tests:** 348 backend, 10 web.

## M4 — Recurring payments and review inbox (2026-09-24)

- **Detection:** charges grouped per account, merchant and currency into 25% amount bands. Monthly payments are
  matched to a day of the month (month end clamped; weekend anchors count from the next business day), yearly ones
  to a date. The DESIGN scoring decides between proposed, possible and nothing. Transfers, cash and refunds are
  excluded.
- **Lifecycle (V6):** `subscription` holds candidates and confirmed payments by state, plus `subscription_rejection`
  and `transaction.subscription_id`. Detection is refreshed after every upload, category change and alias. The user's
  decisions are never overwritten, and rejected patterns are never re-proposed unless they change materially.
- **Review inbox (V7):** one card per merchant, most money first: subscription suggestions and uncategorized
  merchants. Confirm / not recurring / edit, one category for all of a merchant's transactions, skip until the next
  upload, swipe on phones.
- **Committed every month:** home block 3 and the Recurring payments screen, with totals per month and year, groups,
  rename / recategorize / mark ended, history coverage per account and the history nudge. The home figure equals the
  screen for the same month.
- **Tests:** 303 backend, 10 web.

## M3 — Home screen (2026-09-24)

- **Insights:** `GET /api/insights/month` (spent, 3-month baseline and delta, income, net, top 5 + folded rest with
  usual and delta, uncategorized totals, confidence-weighted accuracy) and `GET /api/insights/categories/{id}` (12-month
  trend, average, merchants). One set of `txn.Scope` predicates for figures and lists, so every number drills exactly.
- **Screens (390 px first, light and dark):** home with month switcher and four blocks; transactions with removable
  filter chips, search by text or amount, recategorize inline; category detail with trend and merchants; upload flow
  with per-file questions, account rename and import summary.
- **Banks:** ING Bank Romania Home'Bank parser (multi-line records, running balance), `counterparty_raw` for payee-based
  merchants (V5).
- **Privacy tooling:** statement anonymizer, command line and in the browser (byte-identical), replacing IBANs, cards,
  CNPs, emails, phones, references and people in payer/payee fields; `SamplesGuardTest` blocks leftover PII.
- **Demo:** month-aware fixtures derived from the hand-checked test ledger, so demo drill-throughs add up.
- **Tests:** 264 backend, 8 web.

## M2 — Merchants and categories (2026-09-24)

- **Merchants (V3):** normalizer as a chain of unit-tested steps (cleanup, channel prefixes, web addresses, volatile
  tokens, filler words, trailing location, aliases); `merchant` and `merchant_alias` (45 seeded chains);
  `transaction.merchant_id` assigned on upload and recomputable from raw descriptions.
- **Categories (V4):** 18 seeded categories with kind SPEND / INCOME / TRANSFER; resolver tiers user rule → learned →
  keyword (~130 seeds) → uncategorized, with DESIGN confidences; manual categories (`USER`) are never recomputed.
- **Endpoints:** recategorize a transaction (with "apply to this merchant" rule), undo, `GET /api/categories`;
  debug view `GET /api/merchants`, `GET /api/merchants/explain`, `POST /api/merchants/aliases`.
- **Learning:** two consistent manual edits teach a merchant its category; a disagreement unteaches.
- **Tests:** per-step normalizer tables, samples collapse to 11 merchants, resolver tiers, ≥ 80% spend coverage,
  user decisions survive re-runs and re-uploads; 228 backend tests.

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
