# terminology (local) corpus

Classpath fixtures for `TerminologyCorpusTest`.

- `*.json` — valid JSON **and** clean FHIR parse; triggers **local terminology /
  binding** issues via `FhirInstanceValidator` with the production package-loaded
  chain (`ValidationBeansConfig.localValidationSupportChain`)
- `expectations.tsv` — filename, minimum matching issue count, required message substring

These are **not** JSON syntax errors (`json-malformed/`), **not** FHIR parse errors
(`fhir-parse-malformed/` — e.g. unknown `status` / `item.type` enums that fail at
parse), **not** core `que-*` invariants alone (`que-invariant/`), and **not**
SDC structural / cardinality constraints alone (`sdc/`).

Remote terminology (`tx.fhir.org`) is **out of scope** here — every fixture runs
with an empty remote URL (local-only).

When adding a fixture, update `expectations.tsv`.

## Numbering

| Range   | Category                                      | Typical assertion                                      |
|---------|-----------------------------------------------|--------------------------------------------------------|
| 00–09   | `resource-types` required binding             | `subjectType`, `questionnaire-referenceResource`       |
| 10–19   | `language` (preferred / BCP47)                | not in Common Languages                                |
| 20–29   | `jurisdiction` (extensible)                   | unknown ISO / unknown CodeSystem                       |
| 30–39   | `useContext`                                  | unknown type / value; UCUM on Quantity/Range           |
| 40–49   | `meta.security` / `meta.tag`                  | Unknown code; not in SecurityLabels                    |
| 50–59   | `Questionnaire.code` / `item.code`            | local CS unknown; LOINC/SNOMED/RxNorm unsupported      |
| 60–69   | answerOption / initial / enableWhen Coding    | Unknown code; unsupported CS; coding without system    |
| 70–79   | UCUM                                          | bad unit on Quantity / questionnaire-unit / unitOption |
| 80–99   | Extension-bound codes                         | itemControl, usageMode, choiceOrientation, …           |
| 190–199 | Multi-error combos                            | multi-row TSV                                          |

## Multi-error TSV convention

Combo fixtures may appear on multiple `expectations.tsv` rows (one substring each).

## What local validation can / cannot check (HAPI 8.8.1)

**Does fire locally**

- Required / extensible / preferred bindings against CodeSystems present in core
  FHIR or loaded NPM packages (`hl7.terminology.r4`, UV Extensions, SDC, …)
- UCUM unit validation via `CommonCodeSystemsTerminologyService`
- “CodeSystem is unknown and can't be validated” when the system URI is not loaded
- “The code cannot be checked because codeSystem '…' is not supported” for
  LOINC / SNOMED / RxNorm (CommonCodeSystemsTerminologyService knows the system
  but has no content locally — **not** a remote Tx call)

**Does *not* fire locally (dropped / known gaps)**

- **Wrong display name** for codes in local CodeSystems (administrative-gender,
  item-control, ISO 3166, …) — HAPI’s in-memory / common-CS path does not emit
  “Wrong Display Name” without a remote terminology server
- **LOINC / SNOMED / RxNorm unknown code or wrong display** — only the
  “not supported” warning; true Unknown-code / Wrong-display needs remote Tx
  (see a future `remote-terminology` corpus)
- **`mimeType` extension** — no terminology issue observed for garbage codes
- **`answerValueSet` / `questionnaire-unitValueSet` pointing at a missing
  canonical** — no issue at Questionnaire validation time (expansion is deferred)
- **ICD-10 / many other external systems** — silent (not in
  CommonCodeSystemsTerminologyService)
- **Unknown FHIR enum codes** that fail at **parse** (`status`, `item.type`,
  `enableWhen.operator`, …) — belong in `fhir-parse-malformed/`, not here
- **`answerConstraint` even with a valid code** — the versioned CodeSystem
  `http://hl7.org/fhir/questionnaire-answer-constraint|5.0.0` is not present in
  loaded packages, so validation reports “CodeSystem is unknown” for both valid
  and invalid codes

## Overlap note

`instance-structural/95-subjectType-unknown.json` covers the same binding as
`00-subjectType-unknown.json`. This corpus keeps its own copy so terminology
coverage is self-contained.
