# observation-extract corpus

Classpath fixtures for `ObservationExtractCorpusTest`.

These Questionnaires are valid JSON and clean FHIR parse, but **must fail**
authoring-time observation extract / populate checks (`ObservationExtractValidator`):

- `observationExtract` without a usable `item.code` (system + code)
- `observationLinkPeriod` without a usable `item.code`
- extractable numeric items (`integer` / `decimal` / `quantity`) without a unit
- `unitOption` / `unitValueSet` on a non-`quantity` item
- extracting without `observationExtractCategory` (warning)
- panel / component relationship with missing or shared codes

Rules are extension-triggered (or inherited from a parent `observationExtract`).
`$extract` / populate are **not** executed — that remains in `known-gaps/` (`50`).

## Expectations

`expectations.tsv` columns: `filename`, `minIssues`, `messageSubstring`.
Duplicate filenames are allowed for multi-issue fixtures.

## Numbering

| Range | What |
|-------|------|
| 01 | extract=true, no usable code |
| 02 | `observationLinkPeriod` without code (moved from `known-gaps/49`) |
| 03 | extractable numeric without unit |
| 04 | `unitOption` / `unitValueSet` on non-quantity |
| 05 | extract without category (warning) |
| 06 | panel / component code coherence |
| 07 | inherited extract from Questionnaire root, child missing code |
