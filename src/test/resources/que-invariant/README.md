# que-invariant corpus

Classpath fixtures for `QueInvariantCorpusTest`.

- `*.json` — valid JSON and valid FHIR parse; triggers **instance-validation** `Constraint failed: que-N` issues
- `expectations.tsv` — filename, minimum matching issue count, required message substring

These are **not** JSON syntax errors (see `json-malformed/`) and **not** FHIR parse errors
(see `fhir-parse-malformed/`). They exercise the R4 Questionnaire StructureDefinition invariants
(que-0 through que-13) via `FhirInstanceValidator`.

## Numbering

| Range   | Invariant | Description                                                   |
|---------|-----------|---------------------------------------------------------------|
| 00–09   | que-0     | Name should be a machine-usable identifier (Warning)          |
| 10–19   | que-1     | Group must nest items; display must not                       |
| 20–29   | que-2     | linkIds must be unique across the questionnaire               |
| 30–39   | que-3     | Display items cannot have code                                |
| 40–49   | que-4     | Cannot have both answerOption and answerValueSet               |
| 50–69   | que-5     | answerOption/answerValueSet only on allowed types              |
| 70–79   | que-6     | Display: no required or repeats                               |
| 80–89   | que-7     | operator 'exists' implies answer is Boolean                   |
| 90–99   | que-8     | No initial values on group or display items                   |
| 100–109 | que-9     | Display: no readOnly                                          |
| 110–129 | que-10    | maxLength only on simple question types                       |
| 130–139 | que-11    | answerOption present implies no initial                        |
| 140–149 | que-12    | 3+ enableWhen requires enableBehavior                         |
| 150–159 | que-13    | Multiple initial values only if repeats=true                  |
| 190–199 | multi     | Combo fixtures violating multiple invariants                  |

## Multi-invariant TSV convention

For combo fixtures, `expectations.tsv` has one row per expected `que-*` key against the same
filename. Duplicate filenames are allowed.

## R4 FHIRPath quirks

- **que-5** description says only `choice`/`open-choice`, but the published R4 expression also
  allows `decimal|integer|date|dateTime|time|string|quantity`. Violations only fire when
  `answerOption`/`answerValueSet` appear on: `group`, `display`, `boolean`, `text`, `url`,
  `attachment`, `reference`.
- **que-12** expression is `enableWhen.count() > 2`, meaning **3 or more** `enableWhen` entries
  are required to trigger the constraint. Exactly 2 does **not** fire. (No negative-control
  fixture is included in this corpus; the corpus is failure-only.)
- **que-0** is Warning level (not Rule). The test still asserts its substring.
