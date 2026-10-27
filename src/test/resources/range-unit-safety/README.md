# range-unit-safety corpus

Classpath fixtures for `RangeAndUnitSafetyCorpusTest`.

These Questionnaires are valid JSON and clean FHIR parse. They exercise
authoring-time range / unit / clinical-safety checks
(`RangeAndUnitSafetyValidator`):

- `minValue` greater than `maxValue` (error)
- `minValue` equals `maxValue` (warning)
- `sliderStepValue` larger than `max − min` (error)
- unresolved `unitValueSet` (error)
- unknown UCUM on `questionnaire-unit` / `unitOption` (error)
- curated-vitals unit dimension mismatch (warning)
- curated-vitals physiological bounds (warning)
- `questionnaire-hidden` + `required` (warning)

Control fixture `09` must **not** emit dimension or physiological warnings.

Type-mismatched min/max (known-gaps `21`) is out of scope. `$extract` is not run.

## Expectations

`expectations.tsv` columns: `filename`, `minIssues`, `messageSubstring`.
`minIssues` of `0` means the substring must not appear. Duplicate filenames
are allowed.

## Numbering

| Range | What |
|-------|------|
| 01 | min > max (from known-gaps `20`) |
| 02 | min == max warning (from `86`) |
| 03 | slider step too large (from `91`) |
| 04 | unresolved unitValueSet (from `34`) |
| 05 | unknown UCUM unit |
| 06 | weight LOINC with Celsius unit |
| 07 | SpO₂ max &gt; 100 |
| 08 | hidden + required (from `27`) |
| 09 | HR sane range control |
