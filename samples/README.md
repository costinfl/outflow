# Samples

Golden files for parser, identity and classification tests.

- `synthetic/`: made-up data, see its README.
- `<bank>/`: real exports, **anonymized with `tools/Anonymize.java` first** (see `docs/anonymize.md`).
  `SamplesGuardTest` fails the build on real-looking IBANs, CNPs, emails or card numbers anywhere in this folder.
