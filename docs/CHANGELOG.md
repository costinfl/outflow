# Changelog

Newest first. One entry per milestone. History only, never instructions.

## After the plan — charge reminders (2026-09-25)

- **"Remind me before next charge" (V15):** 1, 3 or 7 days before a confirmed payment's next charge. The review inbox
  shows an "Upcoming charge" card; "Got it" answers it for that charge only.
- **Calendar file:** `GET /api/subscriptions/reminders.ics`, generated locally. It has one repeating event per reminder
  with an alarm, so the phone's calendar reminds even when Outflow is closed.
- **Tests:** 407 backend (5 new), 17 web.

## After the plan — recurring income on the home screen (2026-09-25)

- **Home:** with confirmed recurring income, the Committed block adds "Recurring income X a month" and "Leaves Y a
  month after commitments". The figure is the Recurring screen's for the same month and accounts.
- **API:** `MonthSummary.committed.incomeMonthlyMinor` and `incomeCount`.
- **Tests:** 402 backend (1 new), 17 web.

## After the plan — account picker (2026-09-25)

- **Accounts filter:** up to 4 accounts stay chips. With more, one summary button ("All accounts", "Main + Visa",
  "3 accounts") opens a bottom sheet. It has a checkbox per account grouped Current / Cards / Savings, group
  checkboxes, "Only" per account, and reset. It is still a multi-select kept in `?accounts=`.
- **Fix:** the home screen keeps the filter mounted while its numbers reload.
- **Tests:** 401 backend (unchanged), 17 web (7 new).

## After the plan — standing transfers (2026-09-25)

- **Recurring screen:** DESIGN's "Standing transfers" group. Recurring transfers out (own-account pairs and the
  Transfer category) are detected as of today or a past month, with the own account they go to.
- Recomputed on each read: nothing stored, never a review question, never in the committed totals. Overdue ones show
  as Stopped.
- **Real export:** the scheduled 1,500 a month to savings and about 500 a month to Revolut.
- **Tests:** 401 backend (4 new), 10 web.

## After the plan — recurring income (2026-09-25)

- **Two payments a month:** a band paid on two stable days of the month (a salary's advance around the 25th and the
  rest around the 10th) is two monthly streams; scattered days stay unmatched.
- **Recurring income (V14):** money received in an Income category is detected like payments (`direction` IN).
  Payment and income streams never mix. Missed and changed income raise alerts.
- **Recurring screen:** a "Recurring income" group with its own per-month total, never part of the committed totals.
  The review inbox asks "is this regular income, like a salary?".
- **Real export:** the salary becomes two monthly income streams holding 41 of its 42 payments.
- **Tests:** 397 backend (11 new), 10 web.

## After the plan — people in the review inbox (2026-09-25)

- **Answers by direction (V13):** a category rule can apply to money sent or money received only. A person paid for
  rent who also pays money back is no longer a Housing refund. A rule for one direction splits an earlier rule for both.
- **Review cards** show money sent and received separately, with one picker per direction, "paying me back" (nets the
  sent category) and a one-tap "both ways are a transfer".
- **Real export:** answering the top 3 people takes spending categorized from 22.6% to 78.8%.
- **Tests:** 386 backend (13 new), 10 web.

## After the plan — home insight line and 3-month view (2026-09-25)

- **Insight line:** at most one plain-language line under "Where it went". It names the category whose per-month
  spending moved furthest from its usual, only when the move is more than 25% and more than 100 currency units.
- **"Last 3 months" toggle** (`?months=3`):
  - `months` = 1 or 3 on `/api/insights/month` and `/api/transactions` (`txn.Period`, `Scope.PERIOD`).
  - Figures are totals over the window, shown per month, and compared with the 3 months before it (spec question 28).
  - Drill-throughs carry the window, so figures still equal their lists.
- **Demo:** March shows the insight line; the 3-month view is merged from the demo months.
- **Tests:** 373 backend (9 new), 10 web.

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
