# uv-extensions corpus

Classpath fixtures for `UvExtensionsCorpusTest`.

- `*.json` — valid JSON **and** clean FHIR parse; triggers **UV Extensions**
  instance-validation issues via the production package-loaded chain
  (`hl7.fhir.uv.extensions.r4`)
- `expectations.tsv` — filename, minimum matching issue count, required message substring

These are **not** JSON syntax errors (`json-malformed/`), **not** FHIR parse errors
(`fhir-parse-malformed/`), **not** core `que-0`…`que-13` alone (`que-invariant/`),
**not** core structural misses (`instance-structural/`), and **not** SDC-profile
constraints (`sdc/`).

The test harness uses `ValidationBeansConfig.localValidationSupportChain` so
`hl7.fhir.uv.extensions.r4` (plus SDC / terminology / xver) is loaded.

## Numbering

| Range   | Category                         | Typical assertion                                              |
| ------- | -------------------------------- | -------------------------------------------------------------- |
| 06–07   | Nested-only on simple extensions | `simple extension` / `max allowed = 0`                         |
| 10–79   | Wrong `value[x]` type            | `but found type …`                                             |
| 80–99   | Binding / coding / canonical     | `Unknown code`, `absolute`, empty CodeableConcept warnings     |
| 100–129 | Complex extension structure      | `minimum required`, `matching slice`, nested wrong types       |
| 130–149 | `variable` / Expression          | `var-1`, `exp-1`                                               |
| 150–169 | Wrong extension context          | `not allowed to be used at this point`                         |
| 170–189 | Extra datatype edge cases        | url vs uri, integer vs decimal, etc.                           |
| 190–199 | Multi-error combos               | multi-row TSV                                                  |

## Multi-error TSV convention

Combo fixtures may appear on multiple `expectations.tsv` rows (one substring each).

## Extensions covered

Includes Questionnaire-relevant definitions from `hl7.fhir.uv.extensions.r4`, e.g.:

- Value bounds / format: `minValue`, `maxValue`, `minLength`, `maxDecimalPlaces`,
  `maxSize`, `mimeType`, `regex`, `entryFormat`
- Rendering / behavior: `questionnaire-itemControl`, `questionnaire-choiceOrientation`,
  `questionnaire-hidden`, `questionnaire-usageMode`, `questionnaire-sliderStepValue`,
  `questionnaire-displayCategory`, `questionnaire-signatureRequired`,
  `questionnaire-supportLink`, `questionnaire-supportHyperlink`
- Answers / options: `ordinalValue`, `itemWeight`, `questionnaire-optionExclusive`,
  `questionnaire-optionPrefix`, `questionnaire-optionRestriction`,
  `questionnaire-index-answer`
- Units / references: `questionnaire-unit`, `questionnaire-unitOption`,
  `questionnaire-unitValueSet`, `questionnaire-referenceResource`,
  `questionnaire-referenceProfile`, `questionnaire-referenceFilter`
- Occurs / typing: `questionnaire-minOccurs`, `questionnaire-maxOccurs`,
  `questionnaire-baseType`, `questionnaire-fhirType`, `questionnaire-definitionBased`
- Logic: `variable` (`var-1`), `questionnaire-constraint`, `targetConstraint`,
  `cqf-targetInvariant`
- Artifact / terminology: `artifact-versionAlgorithm`, `artifact-versionPolicy`,
  `artifact-author`, `preferredTerminologyServer`, `designNote`,
  `11179-permitted-value-valueset` / `conceptmap`, `elementdefinition-conceptmap`

## Dropped / unverifiable on HAPI 8.8.1

- **Empty extensions** (no `value[x]`, no nested `extension`): HAPI strips them during
  parse/validation, so `value[x]` min=1 never fires. Nested-only simple extensions
  (forbidden nested + missing value) **do** fire.
- **Empty complex extensions** (constraint / targetConstraint / supportHyperlink with
  zero nested children): likewise stripped before slice min checks. Fixtures that keep
  at least one nested child exercise `minimum required` / missing-slice errors.
- **Empty `Coding` / `CodeableConcept` `{}`**: usually stripped; a CodeableConcept with
  only `id` can still yield a binding warning (`No code provided`).
- **`mimeType` invalid codes**: local terminology does not enforce BCP-13 membership for
  arbitrary codes; use wrong `value[x]` type fixtures instead.
- **Extension value + nested children together**: fails at **FHIR parse** (`HAPI-1811`) —
  belongs in `fhir-parse-malformed/`, not here.
- **Blank `Expression.name` `""`**: fails at FHIR parse (`HAPI-1821`) — parse corpus.
- **`questionnaire-optionRestriction` on `Questionnaire.item`**: still reports
  “not allowed … context invariant …” under this package build; kept as context failures.
- Deprecation **information** messages for `questionnaire-constraint`,
  `cqf-targetInvariant`, `ordinalValue`, `questionnaire-supportLink` often accompany real
  errors; expectations assert the error/warning substrings, not the deprecation text alone.
