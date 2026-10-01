# Synthetic samples

Made-up statements used as golden files. No real person, account or bank; the IBAN is the standard documentation
example `RO49AAAA1B31007593840000`. Real anonymized exports go in `samples/` (one folder per bank).

| File | Parser | Rows | Sum (minor units) | Period |
| --- | --- | --- | --- | --- |
| `generic-2026-01-to-03.csv` | `generic-csv-v1` | 63 | 912726 | 2026-01-01 – 2026-03-26 |
| `generic-2026-02-to-04.csv` | `generic-csv-v1` | 63 | 953449 | 2026-02-01 – 2026-04-26 |
| `ing-ro-2026-q1.csv` | `ing-ro-csv-v1` | 193 | debit 2335532, credit 2553247 | 2026-01-02 – 2026-03-31 |
| `ro-style-2026-02.csv` | `ro-style-csv-v1` (test profile) | 21 | 306308 (debit 543692, credit 850000) | 2026-02-01 – 2026-02-26 |
| `camt053-2026-03.xml` | `camt053-v1` | 8 (of 9 entries; 1 INFO skipped, 1 pending) | 494791 (debit 305209, credit 800000) | 2026-03-02 – 2026-03-14 |

Both generic files come from one Jan–Apr history, so Feb–Mar rows are identical in both (overlap tests, CP1.3).
Each month contains two identical Starbucks rows on the 12th (legitimate duplicates). The history covers monthly rent,
Spotify, Netflix, salary, a savings transfer, variable utility bills and weekly groceries.

`ro-style-2026-02.csv` is February of the same history in a Romanian-bank style: Windows-1250, `;`, `dd.MM.yyyy`,
`1.234,56`, separate debit/credit columns, IBAN in the preamble, diacritics (`Plată`, `BUCUREŞTI`).

The counts and sums above were computed by the generator script, independently of the Java parser.

`camt053-2026-03.xml` is a hand-written ISO 20022 CAMT.053 (001.02 namespace) statement for the documented example
IBAN, with counterparty IBANs from the anonymizer's fake bank (`ANON`). It mixes the 001.02 and 001.08 shapes
(`Sts` as text and as `Sts/Cd`, `Cdtr/Nm` and `Cdtr/Pty/Nm`), two identical Starbucks rows with their own references,
a pending card payment, an INFO entry (not money) and a cash withdrawal described only by `AddtlNtryInf` with a
`DtTm` booking date. Sums by hand: debits 45.10 + 2500.00 + 49.99 + 18.50 + 18.50 + 120.00 + 300.00 = 3052.09,
credit 8000.00, net 4947.91.

`ing-ro-2026-q1.csv` is fabricated data in the exact layout of an ING Bank Romania Home'Bank export: multi-line
records, page chrome (also inside a record), a wrapped detail line, a monthly standing order that reuses one reference,
Round Ups to a savings account, a refund and a cash withdrawal. Balances are consistent on every line.

## Real ING export

`samples/ING Bank Romania/Tranzactii_24-09-2026_11-36-40-anonymized.csv` is the user's real ING Home'Bank export,
anonymized with `tools/Anonymize.java` (people → `PERSON_n`, free-text notes → `NOTE_n`, IBANs, cards, phones and
references replaced; amounts and dates kept). Values computed with a separate Python script from the CSV itself:

| Records | Debit | Credit | Period | Running balance |
| --- | --- | --- | --- | --- |
| 4026 (335 in "August", capitalised in the export) | 101837589 | 102480553 | 2025-01-01 – 2026-09-24 | first 674527; every record follows from the next except one pair in July 2025 (+900 / −900, cancelling) |
