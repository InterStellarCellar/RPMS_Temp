# monitoring-expression-ux corpus

Classpath fixtures for `MonitoringExpressionUxCorpusTest`.

These Questionnaires are valid JSON and clean FHIR parse. They exercise
authoring-time monitoring UX expression checks
(`MonitoringExpressionUxValidator` + escalated linkId rules in
`ExpressionFhirPathValidator`):

- scoring `calculatedExpression` on non-integer/decimal `item.type` (error)
- circular `enableWhen.question` dependency graphs (error); HAPI "comes after"
  forward-ref warnings are suppressed when a circular error is present
- `enableWhen` targeting a `display` item (error)
- contradictory `enableWhen` equals under `enableBehavior=all` (error)
- `readOnly` without `initial` / `calculatedExpression` / `initialExpression` (warning)
- unknown `linkId='…'` literals in `calculatedExpression` (error via ExpressionFhirPath)

Variable self-reference remains in `expression-fhirpath/` (fixture `89`).

## Expectations

`expectations.tsv` columns: `filename`, `minIssues`, `messageSubstring`.
`minIssues` of `0` means the substring must not appear. Duplicate filenames
are allowed.

## Numbering

| Range | What |
|-------|------|
| 01 | scoring calc on `string` item |
| 02 | scoring calc on `integer` (control) |
| 03 | 2-node circular enableWhen (from known-gaps `44`) |
| 04 | 3-node circular enableWhen |
| 05 | readOnly without value source (from known-gaps `65`) |
| 06 | readOnly + calculatedExpression (control) |
| 07 | calculatedExpression unknown linkId (rule 16 escalate) |
| 43 | enableWhen targets display (from known-gaps `43`) |
| 95 | impossible enableWhen all true+false (from known-gaps `95`) |
