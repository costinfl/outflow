# Status

_Resume entrypoint. Updated at every checkpoint._

| | |
| --- | --- |
| Milestone | Plan complete (M0–M5 merged to `main`); post-plan work on real data |
| Last completed | **CP6.18** — CAMT.053 (ISO 20022 XML) statements |
| Next | See "What is left" below; the user picks |
| Branch | `claude/outflow-project-setup-vbwx3f` (`main` + post-plan work) |

## CP6.18 — done

452 backend tests green (7 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API (the golden file uploaded through the Upload screen, then again), light and dark; no console errors.

- **What:** DESIGN roadmap step 2, "Second bank or CAMT.053", and Statement parsing, "Preferred formats: CAMT.053".
- **`ingest.parse.camt.Camt053Parser`** (`camt053-v1`, built in, registered in `ParserConfig`):
  - versions 001.02 to 001.13, elements matched by local name; detection by the CAMT.053 namespace (0.95) or a
    `BkToCstmrStmt` element (0.85);
  - account hint from `Stmt/Acct` (IBAN, currency); statements of two accounts in one file are refused;
  - one row per `Ntry`: booking date (`Dt` or the date of `DtTm`), value date, exact minor units of the entry's
    currency (more decimals than the currency has, a sign or an unknown code fail the file), negative for `DBIT`;
  - `BOOK` posted, `PDNG` pending (no identity reference, so it can still be superseded by its booked version),
    `INFO` skipped (not money);
  - counterparty: the creditor of money out, the debtor of money in (`Nm` or `Pty/Nm`); description = its name, its
    IBAN (for own-account transfer detection) and the remittance text (`Ustrd`, else `AddtlNtryInf`);
  - `AcctSvcrRef` is the identity reference of booked entries; the raw payload keeps the entry's fields;
  - parsed with DTDs, external entities and XInclude off.
- **Golden file** `samples/synthetic/camt053-2026-03.xml` (hand-written, documented example IBAN, `ANON` counterparties):
  8 rows of 9 entries, net 494791, debits 305209, credit 800000; values in its README, computed by hand.
- **Web:** the upload input accepts `.xml` and says "CSV exports or CAMT.053 XML". `docs/parsers.md` describes it.
- **Tests:** `Camt053ParserTest` (6): detection; the golden file's rows, sums, account and fields; amounts in exact
  minor units; hostile (DTD/entity) and inconsistent files refused; an empty statement. `Camt053ImportTest`: detected
  without choosing a parser, the account created from the IBAN, the same file a duplicate, the same statement in
  another file adding nothing. `ImportControllerTest` lists the new parser among the candidates for an unknown file.

## CP6.17 — done

445 backend tests green (4 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API (a RON current account and a EUR card), light and dark; no console errors.

- **What:** "more than one currency at a time" (spec question 14). The user chose a switch over conversion: figures
  stay in their own currency, nothing is converted or estimated, drill-through stays exact. Cross-currency transfer
  pairing (spec question 21) is left for when a second-currency account is uploaded.
- **`txn.Currencies`:** `inUse(accounts)` lists the currencies with transactions in the selected accounts, most used
  first; `slice(currency, accounts)` uses the given currency or, when absent, the accounts' main one (RON with no
  data). An unknown code is a 400 (it used to be a 500 in the insight line).
- **Endpoints:** `/api/insights/month`, `/api/insights/categories/{id}`, `/api/transactions` and `/api/subscriptions`
  default to the main currency instead of always RON, so a EUR-only account shows its own money.
  `MonthSummary.currencies` lists the switch's choices.
- **Web:** `?currency=EUR` joins the URL filters (`useAccountsFilter`: query, cache key, carried by every drill-through
  link). The home screen shows a RON · EUR switch above the period toggle when there is more than one currency; the
  transactions screen sends it and shows it as a filter chip, and its Overview link keeps it.
- **Tests** (`CurrencySwitchTest`): the main currency and the list; EUR figures equal their drill-through, category
  detail and the recurring screen in EUR; the accounts filter choosing its own main currency; bad codes, and RON with
  no data.

## CP6.16 — done

441 backend tests green (5 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API (a 9.99 plan and 11–12 purchases at one merchant, split at 10.50), light and dark; no console errors.

- **What:** DESIGN Subscription candidate lifecycle, "split one (two plans from one merchant)". This closes the DESIGN
  subscription items (merge CP6.15, manual add CP6.14).
- **When it is needed:** amounts within 25% share a band, so a 9.99 plan and occasional 11–12 purchases at the same
  merchant become one "about 9.99, variable" proposal with a poor rhythm. (Two plans on days at least 5 apart were
  already two streams: the twice-monthly split of CP6.5; two plans billed on nearby days are no stream at all.)
- **V17 `subscription_split`:** the user's amount cut per (account, merchant, currency, direction), kept for good.
  - `AmountBands.split(occurrences, cuts)`: amounts below a cut and at or above it never share a band;
    `RecurrenceDetector.Group` carries the cuts, `RecurrenceService.detect` loads them.
  - `AlertService.linkNewCharges`: a confirmed payment claims only charges on its side of every cut (found by the
    end-to-end test: without it the July purchase joined the plan).
- **`POST /api/subscriptions/{id}/split`** `{atMinor}`: only a proposal (409 otherwise), with charges on both sides of
  the amount (400 otherwise; 404 unknown). The cut is stored, the proposal goes, and the refresh proposes each side
  that recurs on its own; the response lists the merchant's proposals afterwards.
- **Review card:** SUBSCRIPTION cards carry `lowestMinor` / `highestMinor` (its charges' range). The Edit form shows
  "Two different things? … Split at" when they differ, validating the amount in range.
- **Tests:** detector (`RecurrenceDetectorTest`, 3): the muddled band without a cut, the clean plan with one, cut
  placement. `SubscriptionSplitTest` (2): the card's range, the split leaving the clean fixed plan (the purchases no
  stream), the cut holding for a confirmed plan on the next upload; the refusals.

## CP6.15 — done

436 backend tests green (3 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API (a service renamed from ZZSTREAM to ZZ STREAM PLUS: two proposals, merged into one), light and dark; no console
errors.

- **What:** DESIGN Subscription candidate lifecycle, "Merge: the user can merge two candidates (same service, two
  merchant keys)". Split (two plans from one merchant) is next, CP6.16.
- **`POST /api/subscriptions/{id}/merge`** `{into}`: the proposal `id` is the recurring payment `into` under another
  merchant name.
  - Its merchant key becomes an EXACT user alias of `into`'s merchant key (the same mechanism as the merchant debug
    view), so its charges, and future imports, are that merchant's. The proposal is deleted.
  - Merchants, categories and subscriptions are recomputed: the charges join `into`. A confirmed `into` keeps the
    user's name, cadence and amount; an `into` the system ended resumes when the merged charges are newer.
  - Refused: 404 unknown; 409 when `id` is not a proposal or `into` is rejected; 400 for itself, the same merchant,
    another account, currency or direction.
- **Web:** the proposal card's Edit form adds "Or is it one you already have, under another name?", listing the
  confirmed recurring payments and the other proposals of the same direction and another merchant; "Same as <name>"
  merges.
- **Tests** (`SubscriptionMergeTest`, today fixed at 20 May 2026): a proposal under the new name joins the confirmed
  payment (six charges, next due moved on, one merchant, the alias kept); two proposals become one; the refusals.

## CP6.14 — done

433 backend tests green (4 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API (a one-off domain renewal marked yearly), light and dark; no console errors.

- **What:** DESIGN Subscription candidate lifecycle, "Manual add: user marks a single transaction as a subscription
  with a cadence — useful for yearly items with only one occurrence in history".
- **`POST /api/subscriptions`** `{transactionId, cadence, name?}` → 201, a CONFIRMED subscription:
  - amount = the charge (FIXED, tolerance 0, band = the amount); due day (and month) from its date, as the detector's
    anchor; next expected = the next due date; money in makes recurring income (`direction` IN);
  - the name defaults to the merchant's; the transaction is linked; confirmed-through = its date, so only later
    charges are price-checked.
  - Later charges link like any confirmed payment's (`AlertService`: near a due date, within 50%): a different amount
    is a price-change question, and no charge by the deadline is a missed-charge question.
  - Refused: already a charge of a recurring payment or proposal (409: confirm the proposal instead), an own-account
    transfer (409), unknown transaction (404), no cadence (400).
- **`TransactionView`** carries `subscriptionId`, `subscriptionName`, `subscriptionState`.
- **Web:** an expanded transaction says "A charge of <name>" (or "Looks like a charge of <name>…" for a proposal);
  otherwise "Mark as a recurring payment" / "Mark as recurring income" opens a cadence choice (Yearly first). The demo's
  Netflix, Enel and salary rows show their recurring payment.
- **Tests** (`ManualSubscriptionTest`, today fixed at 1 March 2026): a yearly renewal marked once, then linked a year
  later two days late; a different price asks, and the late charge answers the missed question; charges imported
  after it link at once, and money in is recurring income; the refusals.

## CP6.13 — done

429 backend tests green (3 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API (Netflix confirmed, Spotify proposed, both in Subscriptions & software), light and dark; no console errors.

- **What:** DESIGN Detail screens, "Recurring payments inside it listed first, separately from variable spending".
- **`Scope.RECURRING`:** a charge of a recurring payment the user confirmed or ended (proposed ones are still guesses).
  `Scope.REVIEWED` now reuses it.
- **`CategoryDetail`:** `recurring` (subscription, name, cadence, state, amount, count; largest first) and
  `recurringMinor`; `merchants` is now the variable spending only. Recurring plus variable is the category's month.
- **Drill-through:** `GET /api/transactions` takes `recurring=true|false` and `subscription=<id>`, so every figure on
  the page opens exactly its transactions (tested: total, recurring, variable, one payment).
- **Web:** a "Recurring payments" section first (each row opens that payment's charges, plus "All recurring payments"
  for the month), then "Variable spending in <month>" with its total. Without recurring charges the page is as before.
  The transactions screen shows "Recurring payments" / "Variable spending" / "<name> (recurring)" filter chips.
- **Demo:** the ledger's Netflix, Enel and salary charges are the demo's confirmed recurring payments.
- **Tests** (`CategoryRecurringTest`): the split and its sum; every figure equals its drill-through; an ended
  payment's charges stay recurring and a rejected one's are variable.

## CP6.12 — done

426 backend tests green (6 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API (Main, Savings, Card statements with a tie), light and dark; no console errors.

- **What:** DESIGN "ties go to review" (spec question 20). A tie is money that matches the same amount in more than one
  other own account equally well (same business-day gap, same IBAN evidence). It stays unpaired, and the inbox now asks.
- **Card `TRANSFER_TIE`** ("Which transfer is this?"): the transaction (account, date, signed amount) and its options
  (account, date, description), closest first. Money affected: the amount. Key `transfer:<transaction id>`; skippable.
  - One card per group of tied transactions, asked through the one with the most partners (money out first, then the
    oldest). Two equal transfers the same day to Savings and Card are one question; answering it settles the other.
- **Answer:** `POST /api/review/transfers/{transactionId}` with `pairWith` (an offered transaction) or nothing ("None of
  these"). 404 when there is no open question for it, 400 for a transaction that was not offered. The pipeline reruns.
- **V16:** `transfer_decision` (out, in, SAME / DIFFERENT), the user's answers, kept for good; `transfer_pair.source`
  (AUTO / USER).
- **`TransferService`:** the user's SAME pairs are made first (while both sides are still candidates), DIFFERENT pairs
  never form, then the automatic matching runs as before. `ties()` lists the open questions; `decide()` stores an
  answer. A later non-transfer category by the user still wins (the pair dissolves on the next run).
- **Web:** the card offers one button per option ("Savings, Mar 3"), "None of these" and Skip. The demo inbox has an
  illustrative tie (1,000 RON out of Main on a Monday, Savings deposits the Friday before and the Tuesday after).
- **Tests** (`TransferTieTest`):
  - a tie is one question listing its options, and the money stays unpaired until answered;
  - picking one pairs them for good (USER), across later uploads and reruns;
  - "None of these" leaves it ordinary and does not ask again;
  - answering one settles the rest of the group (the other pair forms automatically);
  - a later non-transfer category still wins;
  - only an open question and an offered transfer are accepted; the card is skippable.

## CP6.11 — done

420 backend tests green (8 new); typecheck, web tests, build and demo build green. No migration: V6 already allowed
both cadences.

- **What:** DESIGN's table rows, "Bi-weekly: 14 days ± 2, at least 3" and "Quarterly: calendar months, 3, ± 5 days,
  at least 3" (spec question 26).
- **How the anchor works:**
  - Bi-weekly: a weekday in one of the alternating weeks. `anchorDay` is the ISO weekday; `anchorMonth` is the week
    (1 or 2, counted from the epoch).
  - Quarterly: a day of month in one phase of the three-month cycle. `anchorDay` is the day; `anchorMonth` is the
    cycle's first month (1–3, where 1 = Jan/Apr/Jul/Oct).
  - Both use the existing columns within their checks.
- **Guard found by the existing tests:** two weeks or three months apart, purchases on random days often land one
  step apart by chance. Irregular shopping would have become "bi-weekly".
  - So these two cadences also need at least two thirds of their steps on time. The other cadences are unchanged.
  - Two monthly parts (the salary on the 10th and 25th) win over a looser single cadence.
- **Everywhere else:**
  - Monthly equivalents: bi-weekly × 26 ÷ 12, quarterly ÷ 3.
  - Yearly equivalents: × 26 and × 4.
  - Calendar reminders: `FREQ=WEEKLY;INTERVAL=2` and `FREQ=MONTHLY;INTERVAL=3`.
  - Confirming with a corrected cadence anchors on the latest charge, for every cadence.
  - The review Edit form offers both.
- **Real export:** no bi-weekly or quarterly payments (19 monthly, 7 weekly, 29 yearly suggestions). The salary is
  still two monthly parts (41 of 42 payments); categorization and accuracy are unchanged.
- **Tests:**
  - Detector: every other Friday; two days late is on time; every third month; five days late is on time; random
    quarterly shopping is not; weekly and monthly stay put.
  - Equivalents.
  - Calendar rules.
  - Confirming as quarterly or bi-weekly.

## CP6.10 — done

412 backend tests green (5 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API with the real export, light and dark.

- **Accuracy** is now DESIGN's: the share of the period's spending that is categorized **and reviewed**
  (`MonthSummary.reviewedPct`, predicate `Scope.REVIEWED`). This closes spec question 12.
  - Reviewed means the category is the user's own (set by hand, a rule they made in the inbox or with "apply to all",
    or learned from their edits), or a paired own-account transfer, or the charge belongs to a confirmed or ended
    subscription.
  - A keyword's guess is not reviewed.
  - The old confidence-weighted `accuracyPct` stays in the API for reference; the home screen shows `reviewedPct`,
    with "(N% has a category)".
- **"Is this right?" cards** (DESIGN: "5 quick questions would make this 97% accurate"):
  - For merchants whose spending only a keyword categorized, largest money first (all time, like every card), 5 at a
    time; answering one brings up the next.
  - "Right" keeps the category as the user's rule for money sent; "Change" picks another. Both use
    `POST /api/review/merchants/{id}/category` with `direction: OUT`.
  - Card kind `CONFIRM_CATEGORY` with `categoryId` / `categoryName`.
- **Real export:** reviewed spending 0% → **10.1%** after the first five "Right" answers (all 21 months; the rest is
  mostly transfers to people). In the app, one "Right" (ATM cash withdrawals, 26,637 RON) took August from 0% to 8%.
- **Tests:**
  - `ReviewedAccuracyTest` (4): keyword guesses are categorized but not reviewed; five at a time, largest first, the
    next one after an answer; each kind of answer (right, change, confirmed subscription, by hand) adds its money; the
    accounts filter.
  - `RealDataPeopleTest`: the 10.1%.
- **Demo:**
  - March shows 20% reviewed (its confirmed Netflix and Enel charges); the other months 0%.
  - The inbox has a Lidl "Is this right?" card.

## CP6.9 — done

407 backend tests green (5 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API with the real export, light and dark.

- **What:** DESIGN's row action "remind me before next charge". Nothing leaves the machine, and the app is opened only
  now and then, so a reminder lives in two places:
  - **In the app:** from N days before a confirmed payment's next expected charge until its day, the review inbox shows
    an "Upcoming charge" card ("Vodafone charges about RON 95.36 on Sep 30 (in 5 days)"). "Got it" answers it for that
    charge only; the next charge asks again. Skip works like any card.
  - **In the phone's calendar:** `GET /api/subscriptions/reminders.ics` (RFC 5545, generated locally) has one repeating
    all-day event per reminder, with an alarm N days before. Monthly days after the 28th use "the last of 28th…30th
    that exists", so short months keep their reminder, as the app's own due dates do.
- **V15:** `subscription.remind_days_before` (1–14, NULL = off) and `reminded_through` (the charge already answered).
- **API:**
  - `PUT /api/subscriptions/{id}/reminder {daysBefore}`: confirmed payments only (409 otherwise, including income),
    400 outside 1–14, absent or null turns it off.
  - `POST /api/review/reminders/{subscriptionId}` = "Got it".
  - `Subscription` and the Recurring items carry `remindDaysBefore`. Review cards of kind `UPCOMING_CHARGE`.
- **Web:**
  - Recurring rows: a "Remind me before the next charge" choice (no reminder, 1, 3 or 7 days); the row says "reminder
    7 days before".
  - The Recurring screen offers the .ics download once any reminder is set (not in the demo, which has no server).
  - Review: the Upcoming charge card ("today", "tomorrow", "in N days").
- **Tests:** `ChargeRemindersTest` (5), with a clock the test moves:
  - the reminder window, including the day itself;
  - "Got it" and the next charge asking again;
  - validation;
  - the calendar file: CRLF, lines of 75 octets at most, the repeat rules;
  - the repeat rules per cadence.

## CP6.8 — done

402 backend tests green (1 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px against
the API with the real export and on the demo, in light and dark.

- **API:** `MonthSummary.committed` gains `incomeMonthlyMinor` and `incomeCount`. They come from the Recurring screen's
  overview for the same month and accounts, so the home figure always equals its drill-through.
- **Home:** with confirmed recurring income, the "Committed every month" block adds "Recurring income X a month
  (2 payments)" and "Leaves Y a month after commitments" ("Short by" when commitments exceed income). It links to the
  Recurring screen for that month. Without confirmed income, nothing changes.
- **Real export** (Luxoft's two parts and 5 subscriptions confirmed), August 2026: committed 582.60, recurring income
  18,785.00 (2 payments), leaves 18,202.40.
- **Test:** `RecurringIncomeTest.theHomeScreenShowsConfirmedRecurringIncome`: zero before confirming; equal to the
  Recurring screen's figure for June; zero for a month before the salary started.
- Spec question 30 updated: recurring income is on the home screen now.

## CP6.7 — done

Web only; 401 backend tests green (unchanged); typecheck, 17 web tests (7 new), build and demo build green. Checked in
Chromium at 390 px against the API with the real export and 6 accounts (2 current, a card, 3 savings), light and dark.

- **Why** (the user's question: dropdown or tabs?): tabs mean one account at a time, but Outflow's question is answered
  across accounts. "All accounts" is where own-account transfers cancel out. A plain dropdown picks only one. So the
  filter stays a multi-select, and only its presentation changes with the number of accounts.
- **Up to 4 accounts:** the chips, unchanged.
- **More than 4:** one button with a summary ("All accounts", "Main + Visa", "3 accounts") opens a bottom sheet (a
  native modal `<dialog>`: focus stays inside, Escape and a tap outside close it). It has:
  - one checkbox per account, grouped Current accounts, Cards, Savings;
  - a group checkbox (partly checked when the group is partly selected) and an "Only" shortcut per account;
  - "All accounts" to reset, and "Done";
  - under Savings, the note that savings mostly receive transfers.
  - Every tick applies at once and stays in `?accounts=`, like the chips.
- **Rules** in `lib/accountSelection.ts`, tested in `test/accountSelection.test.mjs`: every account selected = no
  filter; the last checked box cannot be unchecked; a group toggle never leaves nothing selected.
- **Fix found on the way:** the home screen replaced everything with "Loading…" while the numbers reloaded, which
  unmounted the filter (and would have closed the sheet on every tick). The filter now stays mounted.
- **The user's savings habit** (for later reading of standing transfers): 5,000 of each salary part goes to savings;
  for the 10th's part, 3,500 right away by hand plus the 1,500 standing order on the 15th. A late or skipped 1,500 is
  therefore expected. The savings account's statement will be uploaded; Revolut may need a new parser.

## CP6.6 — done

401 backend tests green (4 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px
against the API loaded with the real export and on the demo, in light and dark.

- **What:** DESIGN's third Recurring group, "Standing transfers (savings, own accounts — shown but excluded from the
  total)".
  - Recurring money out that is a transfer: paired or provisional own-account transfers, or the Transfer category (for
    example a person the user answered "both ways are a transfer").
  - Found by the same detector, as of today or as of a past month's last day, using only transactions booked by then.
- **Nothing stored, never a question:** recomputed on each read (`RecurrenceService.detectTransfers`), so there is no
  migration, no lifecycle and no review card. Never part of the committed totals, the active count or "Committed
  every month".
- **Stopped:** once the next transfer is overdue (the cadence's tolerance plus the 3-day grace), the transfer shows as
  Stopped and leaves the group's per-month figure (`standingMonthlyMinor`).
- **Where it goes:** `toAccountName` is the own account most of its paired transfers went to.
- **Accounts filter:** only transfers out of the selected accounts.
- **Web:**
  - Rows read "To Savings" when paired, else the merchant name, with a Stopped chip.
  - Each row drills to that transfer's transactions in its latest month.
- **Real export:** once Person_7 is answered as a transfer both ways, two standing transfers show up: about 500 a
  month (active) and 1,500 a month (Stopped, last 16 Aug).
- **Tests:** `StandingTransfersTest` (4):
  - listed with where they go;
  - never commitments nor questions;
  - the accounts filter;
  - a past month sees only what was booked by its end.
- **Demo:** the ledger's March savings transfer is shown as an illustrative monthly standing transfer.

## CP6.5 — done

397 backend tests green (11 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px
against the API loaded with the real export, in light and dark.

- **Why:** the user's salary (Luxoft) comes in two parts, an advance around the 25th of the month before and the rest
  around the 10th. Only spending could be a recurring payment, and two payments a month fit no cadence.
- **Detector:**
  - An amount band that fits no cadence and whose days of month form two clusters at least 5 days apart (around the
    month) is split into two monthly streams, one per cluster.
  - Both halves must fit monthly on their own, or nothing is proposed. Payments on scattered days stay unmatched.
  - The split applies to spending too.
- **Income (V14):** `subscription.direction` and `subscription_rejection.direction` (`OUT` default, `IN`).
  - Money received in an **Income** category is detected like payments. Uncategorized money in is not income until
    the user says so, so people paying back are not proposed.
  - A payment stream and an income stream of the same merchant never match or reject each other.
  - The two parts of a salary each keep their own row: a detected stream goes to the stored row due nearest its day.
  - Alerts work for income: a missed part ("Missed income … Has it stopped?") and a raise ("Income changed"), with
    positive amounts.
- **Recurring screen:** a "Recurring income" group with its own per-month total (`incomeMonthlyMinor`). It is never in
  "Committed every month", the per-month / per-year totals or the active count.
- **Review:** subscription and alert cards carry `direction`. Income cards ask "Recurring income? … Is this regular
  income, like a salary?", naming the day of month so the two parts can be told apart.
- **Real export** (`RealDataPeopleTest`): once Luxoft's money received is Income, it is two monthly income streams
  (the 10th and the 25th), holding 41 of its 42 payments; the 42nd is a one-off 560. In the app: about 9,285 around
  the 10th and 9,500 around the 25th, 18,785 RON a month confirmed. Deposit interest (an Income keyword) is proposed as
  yearly income.
- **Tests:**
  - `RecurringIncomeTest` (8): two parts; income cards; income group never counted as committed; new payments join
    the right part; missed part; raise; a rejected part stays rejected; refresh is idempotent.
  - `RecurrenceDetectorTest`: two days a month → two streams; scattered days → none.
  - The ACME salary in the shared recurring ledger is now expected as income in 4 existing tests.
- **Demo:** the Recurring screen shows an illustrative confirmed salary in "Recurring income".
- **Left:** both parts are named after the merchant; the user can rename them ("Salary advance").

## CP6.4 — done

386 backend tests green (13 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px
against the API loaded with the real export (light), and on the demo (dark).

- **Why:** on the real export, 40 of the 229 inbox cards are people, and they hold about 1.0M of the inbox's 1.46M RON.
  The biggest person moves money both ways (sent 349,321 RON, received 372,201 RON).
  - Before, an answer applied to both directions. Giving that person a spending category would have turned the money
    received into refunds, so spending would have dropped by 372k.
- **V13:** `category_rule.direction` (`IN` / `OUT`, NULL = both). Seed rules and "apply to this merchant" stay NULL.
  - A rule for one direction splits an earlier rule for both: the other direction keeps its category.
  - `categorizeAll` resolves money sent and money received separately. Manual (`USER`) categories are still never
    touched.
- **API:**
  - `POST /api/review/merchants/{id}/category` takes an optional `direction`.
  - Uncategorized cards carry `sentCount` / `sentMinor` / `receivedCount` / `receivedMinor` and `firstDate`.
- **Web (review card):**
  - Sent and received are shown on separate lines ("Money both ways" when there are both), with one picker per
    direction.
  - The received picker offers "Paying me back: less <sent category>", which nets that category's spending.
  - Two-way cards have a one-tap "Both ways are a transfer".
  - A card with money in one direction only answers that direction; money later in the other direction asks again
    (spec question 29).
- **Real data** (`RealDataPeopleTest`): answering the top 3 people takes spending categorized from **22.6% to 78.8%**:
  Person_7 transfer both ways, Person_4 sent → Housing, Person_3 sent → Other.
- **Tests:**
  - `PeopleInReviewTest`: the split card; each direction answered alone; paid back nets the category; received as
    income; a rule for one direction splits a rule for both; a manual category survives; re-import is a no-op;
    400 on an unknown direction.
  - `CategoryResolverTest`: 2 direction cases.
- **Demo:** an illustrative two-way card ("Person A"), like the illustrative subscription cards.

## CP6.3 — done

373 backend tests green (9 new); typecheck, web tests, build and demo build green. Checked in Chromium at 390 px, in
light and dark, on the demo.

- **Insight line** (DESIGN, home block 2): at most one plain-language line under "Where it went", e.g. "▼ Groceries
  down 59% vs. your usual (RON 450.00 vs. RON 1,100.00) — 3 transactions this month."
  - The API picks it (`MonthSummary.insight`): the category whose per-month spending moved furthest from its usual,
    only when the move is more than 25% **and** more than 100 currency units.
  - Uncategorized spending and categories with no usual yet ("New this month") never make the line.
  - It links to that category's transactions.
- **"Last 3 months" toggle** (`?months=3`, in the URL like the accounts filter):
  - `GET /api/insights/month` and `GET /api/transactions` take `months` = 1 or 3; anything else is a 400.
  - `txn.Period` replaces the one-month window; `Scope.PERIOD` replaces `Scope.MONTH`.
  - Details in spec question 28.
- **Web:**
  - The home screen shows "Spent per month, Jan – Mar 2026": the per-month average, with the total below it.
  - Category rows keep the total (it equals the list behind them) and add "≈ X a month".
  - Every drill-through carries `months=3`, except the category screen's own figures, which are one month's.
  - The transactions list shows the range and a removable "3 months" chip.
- **Demo:** March has the Groceries insight. The 3-month view is merged from the demo months, the way the API does it,
  and gives `LastThreeMonthsTest`'s numbers.
- **Tests:** `InsightLineTest` (thresholds at their boundaries, the biggest move wins, uncategorized and new
  categories excluded, per-month in 3-month mode). `LastThreeMonthsTest` (hand-computed window and baseline;
  drill-through equals the figures; `months` = 2 or 6 → 400).

## CP6.2 — done

364 backend tests green; typecheck, web tests and the demo build green.

- **V12:** category `INSURANCE` ("Insurance", SPEND, id 19, listed after Fees). Keywords: ASIGURARI / ASIGURARE /
  INSURANCE / RCA / CASCO and insurers (Allianz, Groupama, Omniasig, Generali, Uniqa, Euroins, Asirom, Signal Iduna,
  Metropolitan Life, NN, Grawe, Helvetia, PAID).
- Recurring insurance premiums are **Bills** on the Recurring screen, as DESIGN groups them. The demo category list
  follows.
- **Real export:** merchant spending categorized 70.3% → **72.8%**, all spending 21.9% → **22.6%** (floors raised to
  72 / 22). Allianz premiums are Insurance.
- The seed-count tests now expect DESIGN's 18 plus Insurance, with Insurance's place in the order checked.

## What is left

From DESIGN, not built yet:
1. ~~**Home:** insight line and "last 3 months" toggle~~ — done in CP6.3.
2. **Recurring:** ~~the "Standing transfers" group~~ (CP6.6); ~~"remind me before next charge"~~ (CP6.9).
3. ~~**Subscriptions:** merge (CP6.15) / split (CP6.16) candidates; "mark this one transaction as a subscription"
   (manual add, CP6.14)~~.
4. ~~**Cadences:** bi-weekly and quarterly~~ (CP6.11).
5. ~~**Category detail:** recurring payments listed first, separately from variable spending~~ (CP6.13).
6. **Review:** ~~people and money both ways~~ (CP6.4); ~~a card for transfer ties~~ (CP6.12); ~~accuracy as
   "categorized and reviewed"~~ (CP6.10).
7. **Money:** ~~more than one currency at a time~~ (CP6.17, a switch); cross-currency transfers (spec question 21).
8. **Banks:** ~~CAMT.053~~ (CP6.18, which also marks pending rows: spec question 23); a second bank's CSV when a sample
   arrives (Revolut).
9. **Pipeline:** DESIGN runs stages G–I as an async job; here they run in the upload transaction. That is fine at
   personal scale.
10. **Later (outside the plan):** household sharing, LLM classification, PDF statements.

From the real data: the biggest uncategorized amount was transfers to people (`PERSON_n`, about two thirds of spending).
CP6.4 lets the inbox answer them by direction; answering the top 3 takes spending categorized from 22.6% to 78.8%.
The largest card left is income from an employer: Luxoft, 366,924 RON received and uncategorized.

## CP6.1 — done

363 backend tests (15 new) and 10 web tests green; typecheck green.

- **Privacy first.** The user's re-anonymized ING export still had private people's first names in free-text
  transfer notes (`Detalii:`), which the anonymizer never touched, and it had been pushed to the public repo.
  - With the user's approval, the upload commit was replaced (force-push of the dev branch; `main` never had it).
  - GitHub may keep the old commit reachable by its id until it is garbage-collected: ask GitHub Support to purge it.
- **Anonymizer** (both implementations, byte-identical, shared golden files regenerated with the Java tool):
  - Detalii / Details / Mesaj / Message / Explicatii values become `NOTE_n` (same text → same note). A quoted cell is
    taken up to its closing quote. ING's own "Suma tranzactiei: X RON" is kept.
  - Lines where a long note wraps (no `Key:` label) are blanked; the ING parser skips empty detail lines.
  - `cnpValid` rejects anything but 13 digits: the Java tool crashed on 10-digit references.
- **The sample**
  (`samples/ING Bank Romania/…-anonymized.csv`) was re-run through the fixed tool. Audit: every payer/payee is
  `PERSON_n` or an organisation, every note is `NOTE_n` or the bank's own text, no unlabelled detail line has content,
  and `SamplesGuardTest` passes.
- **ING parser:** real exports write "August" capitalised; month names are matched case-insensitively. Before this,
  the real file failed at line 1055.
- **`IngRealSampleTest`:** 4,026 records, debit and credit totals, period, 335 August rows. The running balance holds
  except one July 2025 pair that cancels (+900 / −900), and the identity holds over all 21 months. No free-text note
  survives. Expected values are from a separate script (`samples/synthetic/README.md`).
- **Merchant keys:**
  - Payment-processor prefixes are stripped: PAYU*, MOBILPAY*, NETOPIA*, NYX*, MPY*, EP*, PADDLE.NET*, SUMUP*.
    "PayU*eMAG.ro" → EMAG, "MOBILPAY*AMPARCAT" → AMPARCAT.
  - Brands spelled with a digit or two are kept (4+ letters, ≤ 2 digits: 1MINUTE, 7ELEVEN). Store numbers and codes
    are still dropped.
  - Merchant keys are interpretations: identity keys use their own normalizer and are unaffected.
- **V11 seeds:** generic Romanian chains and kinds of business, e.g. MEGAIMAGE, MCD, VENDING, MEDICAL, CLINICA,
  POLICLINICA, LABORATOR, FARM, SMYK, PRIMARK, LEROY MERLIN, DRM, EASYBOX, CINEMACITY, LOTO, AMPARCAT, CNADNR,
  JETBRAINS, ANAF / IMPOZITE / GHISEUL, SCOALA / GRADINITA, TEMU, WOLT; REVOLUT → Transfer (top-ups of one's own
  account). First names (PAUL, DONA) and ambiguous words (MEGA) deliberately left out.
- **`RealDataCategorizationTest`** on the real export:
  - Merchant spending categorized went from 40.8% to **70.3%** (floor 70); all spending from 13.3% to **21.9%**
    (floor 20).
  - The rest of all spending is transfers to people (`PERSON_n`), which only the user can categorize, in the review
    inbox.
  - No merchant key is a processor any more.
  - Importing the file again is a no-op.

## CP5.4 — done

348 backend tests (6 new) and 10 web tests green; typecheck, the build and the demo build green; the spec is refreshed
and `gen:api` is current.

- **`txn.Slice`** (currency + accounts, empty = all) with the SQL `Scope.SLICE`. It replaces `t.currency = ?` in every
  figure and list query:
  - month summary (spent, income, categories, baseline, accuracy, available months)
  - category detail (month, trend, merchants)
  - `TransactionQueries.list`
  - Recurring overview (subscriptions, coverage, suggestion count) and so the home "committed" figure
- **`accounts`** (repeated or comma-separated ids) on `GET /api/insights/month`, `/api/insights/categories/{id}`,
  `/api/transactions` and `/api/subscriptions`. A bad value returns 400; unknown ids simply give no data.
- **Web:**
  - Home has chips (All accounts + one per account, shown with 2+ accounts). The filter lives in the URL
    (`?accounts=1,2`, `lib/accounts.ts`), and every drill-through link carries it: blocks, category page (including
    trend columns), transactions and recurring.
  - The transactions list shows it as a removable chip, and so does the Recurring screen.
  - A filter with no data keeps the chips and says so.
  - The review inbox stays global (every account needs answers).
  - Demo: all demo data is Main's; filtering to Savings shows no data, as the real API would.
- **`AccountsFilterTest` (6):**
  - hand-computed Card-only month (spent, baseline from February only, −35%, categories)
  - every figure equals its filtered drill-through, for all / Main / Card / both
  - accounts add up to all, and both equals all
  - category detail follows the filter
  - committed follows the filter and equals the Recurring screen
  - a bad id returns 400
- **Verified in Chromium against the real API:**
  - Card → March RON 130.00; its spending list totals RON 130.00; the category page keeps `accounts=2`.
  - Removing the chip goes back to all accounts (RON 1,857.88).
  - Main's committed RON 49.99 equals its Recurring screen.
  - No overflow, no console errors.

**M5 acceptance** (plan): a monthly savings transfer is never counted as spending nor proposed as a subscription.
`TransferServiceTest.aMonthlySavingsTransferIsNeitherSpendingNorASubscription` checks this: 6 months paired, spending
0 in every month, nothing detected, no subscription.

## CP5.3 — done

342 backend tests (15 new) and 10 web tests green; typecheck, the build and the demo build green; the spec is refreshed
and `gen:api` is current.

- **Cadences:**
  - `WEEKLY`: anchored to the most frequent weekday, ±1 day, at least 4 charges.
  - `DAILY`: every day, where a Friday → Monday gap over a weekend without charges counts as regular; at least 10
    charges.
  - Periods are weeks or days since 1970, in the same anchor framework as monthly and yearly.
  - `Candidate.anchorDay` is the ISO weekday for weekly and null for daily.
  - Equivalents: weekly × 4.33 / × 52, daily × 30.42 / × 365, integer, rounded half up.
  - The Edit form offers all four cadences. Confirming with another cadence takes the new anchor and next date from
    the latest charge.
- **`recurring.AlertService.check(today)`** runs for confirmed and system-ended subscriptions at the start and end of
  every refresh (V10: `subscription_alert`, `subscription.confirmed_through`):
  - **Future charges link automatically:** same account and merchant, after the last one seen, within ± tolerance + 3
    days of a due date, and within 50% of the expected amount. This works even beyond the detector's 25% band; a
    candidate whose charges all belong to another subscription is not proposed.
  - **Price change:** the latest charge since confirmation (or since the last "Got it") outside expected ± tolerance.
    One open question at a time. "Got it" makes it the expected amount; "Mark ended" ends it (USER).
  - **Missed:** nothing by next expected + tolerance + 3 days. "Still active" skips that period; "Cancelled" ends it
    (USER). A late charge closes it as CHARGED.
  - **Two missed in a row:** ENDED by the system, open alerts closed. It resumes (CONFIRMED) when charges return.
- **Review:** `PRICE_CHANGE` / `MISSED_CHARGE` cards ("Netflix went from RON 49.99 to RON 59.99",
  "World Class usually charges around the 3rd (monthly). Nothing has arrived since Sep 3. Cancelled?"). Money affected
  is the monthly equivalent. `POST /api/review/alerts/{id} {action}`: GOT_IT or END for a price change, STILL_ACTIVE or
  CANCELLED for a missed charge; 400 wrong action, 404, 409 closed.
- **Recurring screen:** chips Active / Price changed / Missed / Ended; an expanded row with an open question links to
  Review. Ending a subscription closes its open alerts.
- **Tests:**
  - `RecurrenceDetectorTest` (+4): weekly with a late day, weekly minimum count, daily over weekends, a missed weekday.
  - `CadenceTest` (+1): weekly and daily equivalents.
  - `SubscriptionAlertsTest` (10):
    - the next charge links and moves the prediction on
    - a price change: Got it, and a second answer gets 409
    - a 40% rise stays the same subscription
    - Mark ended
    - missed on the deadline boundary, then Still active
    - a late charge answers it; two missed → SYSTEM ended, then resumed
    - Cancelled stays ended
    - answer validation
    - a cadence change re-anchors
- **Verified in Chromium against the real API** (with the real date):
  - Netflix confirmed, then a statement with 59.99 charges gave "Price changed".
  - World Class stopped after August and showed "Missed".
  - Got it and Still active moved Netflix to 59.99 (total 309.99) and the gym's next charge to Oct 3.
  - Light and dark, no overflow, no console errors.

## CP5.2 — done

327 backend tests (12 new) and 10 web tests green; typecheck, the build and the demo build green; the spec is refreshed
and `gen:api` is current.

- **Pending rows:** CSV profiles can map `status` + `pendingValues` (`docs/parsers.md`), which gives
  `ParsedRow.pending` and `transaction.status = PENDING`. No bundled format has a status column yet, so this is
  dormant until a bank export has one.
- **An identical posted row** (same identity key) turns the pending one POSTED, the same transaction. An AUTO link on
  it gives way; a USER link does not.
- **V9:**
  - on `transaction`: `superseded_by` and `superseded_source` (AUTO / USER), with a check that only PENDING rows are
    superseded
  - `soft_match_review` (pending, posted, reason, resolution SAME / DIFFERENT)
- **`txn.SoftMatchService.matchAll()`:**
  - Runs in the pipeline after merchants, before categories. The pipeline is now `UploadService.derive()`: merchants →
    soft match → categories → transfers → subscriptions.
  - DESIGN's rule: same account, merchant and currency, same sign, |Δamount| ≤ 5% of the pending amount or ≤ 2
    currency units, dates within 5 days.
  - One candidate on each side links automatically. Several candidates become questions.
  - AUTO links are recomputed every run: a later candidate turns a link into questions. USER links and DIFFERENT
    answers are kept for good.
- **Superseded rows count nowhere:**
  - `Scope.MONTH` excludes them, so every figure and every list drops them
  - also excluded from available months, recurrence (and unlinked from subscriptions), transfer pairing and
    uncategorized cards
  - a pending row counts as spending until it is replaced
- **Review:**
  - `POSSIBLE_DUPLICATE` cards: "Same RON 250.00 at Omv, pending on Mar 29 and posted on Mar 30. Is it one payment?",
    with Same · Different · Skip; swipe right = Same, left = Different
  - `POST /api/review/duplicates/{id} {same}`: 204; 404 unknown, 409 already answered, 400 without `same`
  - Answering re-runs the derived stages.
- **Transactions list:** a "Pending" label; `TransactionView.status`.
- **Tests:**
  - `ConfigurableCsvParserTest` (+2): status parsing, and status without `pendingValues` rejected.
  - `SoftMatchServiceTest` (10):
    - an identical posted row upgrades the pending one; a single match supersedes it
    - amount, date and sign bounds (unit)
    - other merchants and accounts never match
    - a later candidate dissolves an AUTO link into questions
    - review cards; Same links for good and survives later uploads; Different is remembered and the remaining match
      links; bad answers
    - a superseded pending row's transfer pair moves to the posted row
- **Verified in Chromium against the real API**, with a temporary status profile from a scratch folder (nothing
  committed):
  - EMAG pending 120.00 was linked to posted 123.50 automatically.
  - OMV pending 250 against posted 250 / 251 gave two cards; March showed RON 954.50 with the pending row labelled.
  - "Same" on the second card → "Nothing needs your attention", March RON 704.50.
  - Light and dark, no overflow, no console errors.

## CP5.1 — done

315 backend tests (12 new) and 10 web tests green; typecheck, the build and the demo build green; the spec is refreshed
and `gen:api` is current.

- **V8:**
  - `transfer_pair` (out, in, method IBAN or AMOUNT_DATE, business days)
  - on `transaction`: `transfer_pair_id`, `transfer_state` (PAIRED / PROVISIONAL), `transfer_account_id`, with a
    consistency check
- **`txn.TransferService.pairAll()`** runs in the upload pipeline after categories and before subscriptions (DESIGN
  stages G–I, run synchronously in the same transaction).
  - **A pair** is money out of one own account and the same amount into another, same currency, within 3 business
    days. Accounts must differ.
  - **Counterparty IBANs:** found in the counterparty and description text, HMAC-hashed and compared with the
    accounts' `iban_hash`.
    - They rule out pairs with any other account.
    - They confirm a pair: IBAN-confirmed pairs win within the same gap.
    - On their own they mark a one-sided transfer as PROVISIONAL. It becomes PAIRED when the other statement arrives.
  - **Greedy by smallest gap.** If a transaction has two equally good partners at a level, it and those partners stay
    unpaired.
  - **Recomputed on every run:** only the difference is applied, so the result does not depend on upload order. A
    later upload can turn a pair into a tie; the pair is then dissolved and its SYSTEM category recomputed.
  - Paired and provisional transactions get category TRANSFER, source SYSTEM. `categorizeAll` leaves them alone.
  - A user's non-transfer category excludes a transaction from pairing (a user decision wins).
  - Recurrence ignores transfers.
- **Import summary** (`FileOutcome.transfers`, `ImportSummary.transfers`): "2 transfers between your accounts,
  excluded from spending."
- **`TransactionView.transferState` / `transferAccountName`:** the expanded row says "Transfer to your Savings account:
  both sides found…" or explains the provisional state. The demo's savings transfer is shown as provisional.
- **`TransferServiceTest` (12):**
  - pairing within 3 business days (Fri → Wed); 4 days apart is not a pair
  - same account and different amounts never pair
  - greedy by smallest gap
  - a tie dissolves an earlier pair, and an IBAN decides a tie
  - rerunning is idempotent
  - provisional via IBAN, then paired; an unknown IBAN is ignored
  - a user category is never overwritten; `categorizeAll` keeps transfers
  - business-day counting
  - **M5 acceptance:** 6 monthly savings transfers are paired, spending is 0 in every month, nothing recurring is
    detected and no subscription is proposed
- **Verified in Chromium (real API):** uploaded Main and Savings statements via `#/upload` and answered the account
  questions. The summary said "2 transfers between your accounts, excluded from spending". March spending was
  RON 120.00 (the 1,000 transfer excluded), and the row explained the transfer. No console errors.

## CP4.4 — done

303 backend tests and 10 web tests green; typecheck, the build and the demo build green; the spec is refreshed and
`gen:api` is current.

- **`GET /api/subscriptions?month=&currency=`** returns the Recurring screen:
  - Confirmed and ended subscriptions, grouped into Subscriptions and Bills (the latest charge's category is
    Utilities, Telecom, Housing or Fees). Within a group, counted rows come first, then the highest monthly equivalent.
  - Each row has its cadence, amount, next expected date, Active or Ended, its monthly and yearly equivalents
    (`Cadence.monthlyMinor`: yearly ÷ 12, rounded half up, in `long`) and whether it is counted.
  - The per-month and per-year totals are sums of the counted rows.
  - Without a month, confirmed rows count. With a month, rows that had started by the month's end are listed, and
    they count when confirmed or ended no earlier than its first day.
  - It also returns per-account coverage (first and last date, months spanned; monthly detection at 3+ months,
    yearly at 13+) and the number of suggestions waiting.
- **`PATCH /api/subscriptions/{id}`** renames a confirmed or ended subscription. A blank name returns 400, an unknown
  id 404, a proposal 409.
- **`MonthSummary.committed`** (monthly equivalent, count, share of spent) is computed through the same
  `RecurringService.overview(month)`, so the home figure equals the Recurring screen for that month.
- **Web:**
  - Home block 3 shows the total, "N recurring payments · X% of this month's spending", and opens
    `#/recurring?month=`.
  - `#/recurring` shows totals per month and per year, the groups with subtotals, and Active / Ended chips.
  - A row opens to rename, change category (all from the merchant) or mark ended.
  - It also shows the coverage per account, a link to waiting suggestions, and DESIGN's history nudge instead of an
    empty list. The "As in <month>" chip returns to today.
- **Tests:**
  - `CadenceTest`: equivalents, including half-up rounding.
  - `RecurringControllerTest` (4):
    - totals, groups and order, with ended rows listed but not counted
    - a past month counts what was active then and equals the home figure (February, December 2025)
    - coverage for 29-month and 2-month accounts
    - rename and its errors, and a bad month
- **Verified in Chromium against the real API at 390 px, light and dark:**
  - home June 2026 showed "RON 339.67 · 4 recurring payments · 30%", and the Recurring screen had the same total
  - rename and mark ended updated the totals
  - at 320 px nothing overflows: totals stack below 360 px, and the header nav, which CP4.3's Review link had pushed
    past 320 px, now wraps
  - Demo: fixtures computed with the backend's rule, and the home figure equals the Recurring screen for each month.
- **Deferred:**
  - the "Standing transfers" group (transfers are never detected as subscriptions; it comes with M5 transfer pairing)
  - Price changed / Missed chips (CP5.3)
  - "remind me before next charge"
  - category detail listing recurring payments first

**M4 acceptance** (plan): seeded fixtures with known subscriptions are detected: fixed (Netflix), variable (Enel),
month end (Orange, anchor 31), one missed month (World Class), plus a plan next to one-offs (eMAG) and a yearly
payment. Rejected items never return: `SubscriptionServiceTest` checks this after new charges, and checks that only a
material change (+60%) re-proposes. Both are automated.

## CP4.3 — done

297 backend tests and 10 web tests green; typecheck, the build and the demo build green; the spec is refreshed and
`gen:api` is current.

- **`GET /api/review`** returns `{cards, possible, count}`, one card per merchant, sorted by money affected:
  - **Subscription suggestion:** a PROPOSED row. It carries the charges linked to it, cadence, amount, since and next
    expected. Confidence ≥ 0.65 goes in `cards`; lower goes in `possible`.
  - **Uncategorized merchant:** all of a merchant's transactions without a category, with their count and money.
  - Price-change, missed-charge and duplicate cards come with M5.
- **Skipping:** `POST /api/review/skip {key}` (V7 `review_skip`). A skipped card stays hidden until the next upload.
- **Answers:**
  - `POST /api/subscriptions/{id}/confirm` (optional name, cadence, expected amount), `/reject` and `/end`. An unknown
    id returns 404; an illegal transition returns 409.
  - `POST /api/review/merchants/{id}/category`: a USER rule for the merchant, which all its automatic transactions
    follow.
  - Category changes (this endpoint and `PUT`/`DELETE /api/transactions/{id}/category`) and merchant aliases re-run
    `SubscriptionService.refreshNow()`. A merchant marked as a transfer stops being a subscription candidate.
- **`#/review`** (Review in the header; "N questions to review" on the home screen's attention block):
  - Subscription cards read "Netflix, RON 49.99 monthly since January 2026. Is this a subscription?" with Yes /
    Not recurring / Edit (name, amount, how often) / Skip.
  - Uncategorized cards read "6 transactions, RON 1,500.00 from World Class" with a category picker, Apply and Skip.
  - Swipe right confirms and left rejects (or skips a merchant card); the buttons do the same. "Possible" payments
    are collapsed. Typed amounts are parsed exactly (`decimalToMinor`, with a web unit test).
- **Tests:** `ReviewControllerTest` (7) covers the order and contents of cards, confirm / reject / end with 404, 409
  and 400, a merchant category that also drops its subscription proposal, a transaction recategorized as a transfer
  that drops its proposal, and skipping until the next upload.
- **Verified in Chromium at 390 px, light and dark, against the real API with the seeded ledger:**
  - home badge "6 questions to review" → inbox
  - Yes (Netflix); Edit → "Orange phone" at 70,5 (stored as 7050); Not recurring (eMAG); World Class → Health & pharmacy
  - Skip, then a left swipe rejected Enel → "Nothing needs your attention"
  - no overflow, no console errors
  - Demo: illustrative cards consistent with the demo ledger; answering explains that it needs the real app.

## CP4.2 — done

290 backend tests green (10 new); typecheck green. There is no API for this yet; it comes with the inbox in CP4.3.

- **V6:**
  - `subscription`: DESIGN's columns, plus the name, the amount band, the confidence, first and last seen, `ended_by`
    and `decided_at`. States PROPOSED / CONFIRMED / REJECTED / ENDED; cadences allow DESIGN's full list.
  - `transaction.subscription_id`, and `subscription_rejection`.
- **`SubscriptionService.refresh(today)`** runs in the upload pipeline after categories, in the same transaction. The
  date comes from a `Clock` bean.
  - A new stream becomes PROPOSED unless a rejection covers it; its charges are linked.
  - A PROPOSED row gets the detected fields again and its links are re-synced.
  - A CONFIRMED row gets its new charges linked and its last seen and next expected dates moved on. Name, cadence and
    amount stay the user's.
  - A SYSTEM-ended row resumes on a newer charge.
  - A PROPOSED row that is no longer detected is dropped and its links are cleared. CONFIRMED, ENDED and REJECTED rows
    are never touched.
  - Streams are matched by account, merchant, currency and amount bands that overlap within 25%. Cadence is not
    compared, so a user's correction survives.
- **User transitions:**
  - `confirm(id, edits)`: PROPOSED or ENDED → CONFIRMED, optionally with a new name, cadence or expected amount.
  - `reject(id)`: PROPOSED → REJECTED. Its charges are unlinked and a rejection row is written.
  - `end(id)`: CONFIRMED → ENDED by the user.
  - Anything else throws `TransitionException`.
- **`SubscriptionServiceTest`:**
  - an upload proposes the 5 seeded subscriptions and links their charges (eMAG's two one-off purchases stay unlinked)
  - refresh is idempotent
  - a rejected subscription never returns, even after new charges, while a +60% price is proposed again
  - confirmed edits survive refresh, and new charges link and move the dates on
  - a confirmed subscription stays when the detector loses it
  - stale proposals are dropped
  - user-ended stays ended; system-ended resumes
  - illegal transitions are refused
- Deferred: merge/split and manual add (DESIGN lifecycle bullets) belong with the screens (CP4.3/CP4.4). Refresh also
  runs only on upload; recategorizing or adding an alias re-runs it with the inbox API in CP4.3.

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
  - `RecurrenceServiceTest` on a seeded 7-month ledger (`RecurringFixtures`): Netflix fixed, Enel variable, Orange month
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

- The first ING sample contained real names of private people (Beneficiar / Ordonator fields). The repository was made
  private and, at the user's request, the file was purged from the dev branch history (force-push, 2026-09-24; `main`
  never had it). The ING parser's golden test now uses `samples/synthetic/ing-ro-2026-q1.csv`. The anonymizer now
  replaces people in Beneficiar / Ordonator / Plătitor fields (both implementations, byte-identical); next the user
  re-anonymizes the export and a real golden test is added. GitHub may keep unreferenced old commits cached: ask GitHub
  Support to purge them (the repository is public again since 2026-09-24).

- Merchant key `Person_6/7082938682055` on the real export: a reference number glued to a name with `/` survives
  normalization, so that person gets a second merchant. One transaction; not fixed yet.
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
    **Revisited in CP6.10:** the home screen now shows DESIGN's "categorized and reviewed" share (`reviewedPct`),
    raised by the inbox's "Is this right?" cards.
13. **Uncategorized money in** is neither income nor spending (it may be a refund or an own-account transfer). It
    lowers nothing on the home screen; M4's review inbox will surface it. Uncategorized money *out* counts as spent
    (conservative: better to over- than under-state spending).
14. **One currency at a time.** Insights take a `currency` parameter; absent, the selected accounts' main currency
    (CP6.17). Transactions in other currencies are not converted: the home screen switches between currencies (the
    user's choice over conversion with entered or derived rates).
15. **Baseline = previous 3 months that have data.** With one month of history the average uses that one month;
    `baselineMonths` tells the UI to show the "upload more history" nudge.
16. **ENDED → CONFIRMED "when charges resume" vs. "a user decision is never overwritten".** Resolved as
    `subscription.ended_by`: SYSTEM-ended (two missed charges, CP5.3) resumes by itself when a newer charge arrives;
    USER-ended stays ended, and its stream is not proposed again. The user can confirm it again by hand.
17. **`tolerance_pct` → `tolerance_minor`.** DESIGN's variable tolerance is 2 × MAD, an amount; it is stored in minor
    units so no amount goes through floating point. A percentage can be shown in the UI.
18. **Rejection key.** DESIGN keys rejections by (household, merchant, amount band). They also store cadence and
    currency, because "a new cadence" re-proposes, and the amount is the rejected expected amount (±50% = same band).
19. **"Committed every month" is not a transaction sum.** DESIGN defines it as monthly equivalents of confirmed
    recurring payments (yearly ÷ 12), so it cannot be a `txn.Scope` sum. Its drill-through is the Recurring screen for
    the same month, whose rows sum to it exactly (same `RecurringService.overview`).
20. **Transfer ties.** DESIGN: "ties go to review". Tied transactions stay unpaired (so they count as ordinary money
    out/in) until a later statement, an IBAN or the user decides: CP6.12's "Which transfer is this?" card.
21. **Cross-currency transfers** (FX tolerance) are not paired yet: every account so far is RON. They need a rate
    source that does not leave the machine; proposal: a user-set tolerance per currency pair, when a second currency
    appears.
22. **Transfers without an IBAN on one side only** (the other account not uploaded, no IBAN in the text) are still
    caught by the TRANSFER keyword seeds (ECONOMII, CONT PROPRIU, …), as before.
23. **Pending rows need a bank format that marks them.** ING Home'Bank exports only booked rows, as far as the purged
    sample showed. CAMT.053 does (`Sts` PDNG, CP6.18), so the mechanism is now fed by it.
24. **A duplicate card is about two transactions,** not one merchant (DESIGN: "one card = one merchant"). A pending row
    with two posted candidates gets two cards, one per pair; answering one settles the other.
25. **Two missed charges end a subscription automatically** (DESIGN: "Two missed in a row → propose state ENDED";
    the lifecycle diagram: "user or 2 missed"). It is ENDED by the SYSTEM rather than proposed: it resumes by itself
    when charges return, so nothing the user decided is overwritten.
26. **Bi-weekly and quarterly** (in DESIGN's table, not in the plan's checkpoints). Done in CP6.11; they also need
    two thirds of their steps on time (random purchases fall one step apart by chance at those spacings). They fit the
    same anchor framework (two-week periods; three-month periods ±5 days) when wanted.
27. **Insurance category** (decided by the user: add it). DESIGN's seed list had none; V12 adds "Insurance" (SPEND,
    Bills on the Recurring screen).
28. **What "last 3 months" means** (DESIGN names the toggle only; my reading, open to change):
    - The window is the selected month and the two before it.
    - Every figure is a **total** over the window, so it still equals its drill-through. The home screen shows
      spending as a per-month average: the total divided by the window's months that have data.
    - "Usual" and the deltas compare that per-month average with the average of the 3 months **before** the window
      (only those with data).
    - The insight line uses the same per-month figures and thresholds (> 25% and > 100 units a month).
    - "Committed every month" is still as of the selected month; its share is of the per-month spending.
29. **Answers by direction** (DESIGN: "one card = one merchant"; the card stays one per merchant, answered per
    direction; my reading, open to change):
    - An inbox answer covers only the directions the card shows. For a merchant only ever paid, a later refund from
      it comes back as a question instead of being guessed.
    - "Apply to this merchant" from the transaction list still covers both directions (refunds net the category).
    - Money received in a SPEND category is a refund: it reduces that category's spending, as before.
30. **Recurring income** (my reading, open to change): only money received in an INCOME category is checked, so an
    answer in the review inbox comes first. Its per-month total is listed on its own; the home screen shows it in the
    Committed block with what it leaves after commitments (CP6.8). A salary in two parts is two streams, not one.
