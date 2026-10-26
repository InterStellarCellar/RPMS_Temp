# FHIR parse-malformed corpus

Classpath fixtures for `FhirParseMalformedCorpusTest`.

- `*.json` — valid JSON that triggers HAPI FHIR **parse** errors (`DataFormatException` / `CollectingParserErrorHandler`)
- `expectations.tsv` — filename, minimum issue count, required message substring

These are **not** JSON syntax errors (see `json-malformed/`) and **not** instance-validation issues (`FhirInstanceValidator`).

When adding a fixture, update `expectations.tsv` and the mirror under `dont-commit/examples/fhir-parse-malformed/`.
