# CSV parser profiles

Every CSV export format is one YAML file. The shipped ones live in `api/src/main/resources/parsers/`. To add a bank
without rebuilding, point `outflow.parsers.locations` at a directory as well, e.g.
`OUTFLOW_PARSERS_LOCATIONS=classpath:parsers/*.yml,file:/config/parsers/*.yml`.

```yaml
id: my-bank-csv-v1          # stored on each imported file; lowercase, digits, dashes. Never reuse an id for a changed format: bump -v2.
name: My Bank CSV           # shown in the upload screen's parser override
encoding: UTF-8             # or windows-1250 (older Romanian exports), ISO-8859-2, ...
delimiter: ","              # one character: "," ";" "\t"
dateFormat: dd.MM.yyyy      # java.time pattern for the booking date
valueDateFormat: dd.MM.yyyy # optional; defaults to dateFormat
decimalSeparator: ","       # "." (default) or ","
groupingSeparator: "."      # optional thousands separator: "." "," or " "
currency: RON               # fixed currency, OR map columns.currency (exactly one of the two)
ibanInPreamble: true        # optional; look for the account IBAN in the lines above the header
columns:                    # header names as they appear in the file (case-insensitive)
  bookingDate: Data tranzactie
  valueDate: Data valuta    # optional
  amount: Suma              # signed amount, OR debit + credit (unsigned, one per row)
  # debit: Debit
  # credit: Credit
  # currency: Moneda
  description: [Descriere, Beneficiar]  # one or more columns, joined with a space
  reference: Referinta      # optional bank reference, used for identity when present
  accountIban: IBAN         # optional column holding the account's own IBAN
```

Rules the parser enforces:

- The header row is the first line containing every mapped column; lines above it are the preamble.
- Amounts are parsed exactly into minor units. An amount with more decimals than the currency allows, or one
  that doesn't match the configured separators, is an error.
- Any row that cannot be read fails the whole file with its row number. Rows are never silently skipped.
  Blank lines are ignored.
- Unknown YAML keys are an error, so a typo never silently drops a column.
