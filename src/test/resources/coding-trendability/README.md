# coding-trendability corpus

Classpath fixtures for `CodingTrendabilityCorpusTest`.

These Questionnaires are valid JSON and clean FHIR parse. They exercise
authoring-time coding / terminology checks (`CodingTrendabilityValidator`):

- value-producing item missing `item.code` when monitoring intent is present
  (`url`+`version`, observation extract / link-period, or scoring
  `calculatedExpression`) (error)
- plain forms without monitoring intent do not require `item.code`
- `item.code` / `answerOption.valueCoding` missing system or code (error)
- `example.org` / `example.com` CodeSystem (error)
- custom (non-example) CodeSystems are allowed
- duplicate `item.code` across questions is allowed
- `calculatedExpression` scoring a choice without `ordinalValue` / `itemWeight` (error)

Control fixture `07` must **not** emit those errors.

Does not look up codes on a terminology server. `$extract` is not run.

## Expectations

`expectations.tsv` columns: `filename`, `minIssues`, `messageSubstring`.
`minIssues` of `0` means the substring must not appear. Duplicate filenames
are allowed.

## Numbering

| Range | What |
|-------|------|
| 01 | missing `item.code` |
| 02 | `answerOption.valueCoding` without system (from known-gaps `90`) |
| 03 | example CodeSystem (from known-gaps `69`) |
| 04 | custom CodeSystem allowed |
| 06 | calculatedExpression missing ordinals |
| 07 | PHQ-style control |
