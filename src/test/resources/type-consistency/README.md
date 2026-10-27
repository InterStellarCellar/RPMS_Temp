# type-consistency corpus

Classpath fixtures for `TypeConsistencyCorpusTest`.

These Questionnaires are valid JSON and clean FHIR parse. They exercise
authoring-time type-agreement checks (`TypeConsistencyValidator`):

- `answerOption.value[x]` vs `item.type` (error)
- `initial.value[x]` vs `item.type` (error)
- `enableWhen.answer[x]` vs referenced question `item.type` (error; skip `exists`)
- `minValue` / `maxValue` extension value vs `item.type` (error)
- multiple `initialSelected` without `repeats` (error)

Control fixture `01` must **not** emit type-mismatch messages.

## Expectations

`expectations.tsv` columns: `filename`, `minIssues`, `messageSubstring`.
`minIssues` of `0` means the substring must not appear. Duplicate filenames
are allowed.

## Numbering

| Range | What |
|-------|------|
| 01 | matching answerOption control |
| 10–12 | answerOption type mismatch (from known-gaps) |
| 11 (quantity) | answerOption Coding on quantity (from known-gaps `85`) |
| 13–15 | initial type mismatch |
| 16–19 | enableWhen answer type mismatch |
| 21 | minValue type mismatch |
| 42 | multiple initialSelected without repeats |
