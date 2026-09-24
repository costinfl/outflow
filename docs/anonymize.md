# Anonymizing a real statement for `samples/`

Real exports make the parser and merchant normalizer good. They must be anonymized **on your machine, before**
they are shared with anyone, including Claude or GitHub. `tools/Anonymize.java` does that locally, with plain Java
21 (no dependencies, no network).

## In the browser (easiest)

Open the app (or the public demo) at `#/anonymize`, pick the export, optionally add names and a seed, read the report,
and download the result. The file is read and processed in the browser: nothing is uploaded or stored, and the page
makes no network request with it (checked by an end-to-end test). The browser version writes exactly the same bytes as
the command-line tool for the same seed and names, so both can be mixed across exports.

## On the command line


1. Keep the raw export **outside** the repository, e.g. `~/Downloads/export.csv`. The tool warns if it is inside one.
2. Optional: write a names file **outside** the repository, one name per line, for people who appear in transfers
   (yourself, family, people you pay). `# comments` are allowed. "First Last" is also matched as "Last First", with or
   without diacritics. The account holder after "Titular:" / "Nume:" / "Client:" is found automatically.
3. Run, from the repository root:

   ```
   java tools/Anonymize.java --in ~/Downloads/export.csv --out samples/<bank>/<bank>-2026-01.csv \
        --names ~/names.txt --seed "a private phrase you reuse"
   ```

   Use the **same private seed** for every export of the same accounts: the same IBAN or name then maps to the same
   fake in every file, so overlapping exports still overlap. Never commit the seed or the names file.
4. **Read the report** it prints (every replacement, originals shown only partially) and the lines it asks you to
   review: transfer lines can contain names it does not know. Add those names to the names file and run again.
5. **Open the output file and read it.** Then commit only the output, and tell Claude the bank and file.

## What changes and what stays

| Replaced | With |
| --- | --- |
| IBANs (valid checksum) | fake IBAN: same country and length, bank code `ANON`, valid checksum |
| Card numbers (Luhn-valid) and the 4 digits next to masks / "card" | fake digits |
| Account holder + names from `--names` | `PERSON_1`, `PERSON_2`, … |
| CNP (valid checksum) | 13 fake digits |
| Emails, Romanian mobile numbers | `person1@example.invalid`, `0700…` |
| Digit runs of 10+ (customer codes, references) | fake digits, same length |

Kept exactly: amounts, dates, merchant text, encoding, delimiter, quoting, preamble lines, line endings.

`SamplesGuardTest` fails the build if anything in `samples/` still holds a real-looking IBAN (anything but `ANON`
fakes and the documented example), a valid CNP, a real email or a card number.

## Limits

No tool finds every personal detail in free text. The names file, the report and your own read-through are part of
the process, not optional.
