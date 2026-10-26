# expression-fhirpath corpus

Classpath fixtures for `ExpressionFhirPathCorpusTest`.

These Questionnaires are valid JSON and clean FHIR parse, but **must fail**
authoring-time FHIRPath checks (`ExpressionFhirPathValidator`):

- `IFhirPath.parse` rejection (broken syntax, unknown functions)
- variable self-reference
- unknown `linkId='…'` literals (warning; asserted where relevant)

CQL and `application/x-fhir-query` are **not** compiled here — those remain in
`known-gaps/` (`09`, `72`, `73`).

## Expectations

`expectations.tsv` columns: `filename`, `minIssues`, `messageSubstring`.
Duplicate filenames are allowed (syntax error + unknown linkId on the same fixture).

## Numbering

Moved from the previous `known-gaps` `expression-not-compiled` / related bands:

| Range | What |
|-------|------|
| 00–08 | Invalid `text/fhirpath` (and implied constraint `valueString`) |
| 52–53 | `itemPopulationContext` / `itemExtractionContext` bad FHIRPath |
| 71 | Missing `linkId` literal (also rejected as unknown function `.sum()`) |
| 74 | `enableWhenExpression` only, invalid FHIRPath |
| 80 | Combo: type mismatch + bad FHIRPath |
| 89 | Variable `%name` self-reference |
