# remote-terminology corpus (mocked)

Classpath fixtures for `RemoteTerminologyCorpusTest`.

- `*.json` — valid JSON, clean FHIR parse; terminology issues exercised with a **mocked**
  FHIR terminology server (`MockFhirTerminologyServer`)
- `expectations.tsv` — filename, min matching issues, message substring, mock mode
- `mock-modes.tsv` — optional index of non-`DEFAULT` modes (also encoded in `expectations.tsv`)

These are **not** JSON syntax errors, **not** FHIR parse errors, and **not** local-only
structural/`que-*` suites. They specifically exercise
`RemoteTerminologyServiceValidationSupport` via
`QuestionnaireValidationService.validate(json, remoteUrl)`.

## How the mock works

HAPI remote Tx support:

1. Searches `GET {base}/CodeSystem?url=…` / `ValueSet?url=…` → `isCodeSystemSupported` /
   `isValueSetSupported`
2. Calls `POST {base}/CodeSystem/$validate-code` or `ValueSet/$validate-code` with
   `Parameters` (`system`, `code`, optional `display` / `url`)

Default mock policy:

- Every CodeSystem/ValueSet search returns a stub resource (remote claims support)
- `$validate-code` returns `result=false` when the code matches bad-code patterns
  (`0000-0`, `BAD-*`, `not-a-*`, …) or the display matches wrong-display patterns
  (`Wrong …`, `WRONG:…`, `Totally wrong`, …)
- Otherwise `result=true`

Failed validations are wrapped by HAPI as messages containing
`The Remote Terminology server`.

## Numbering

| Range   | Category                                      |
|---------|-----------------------------------------------|
| 00–09   | `language` / `subjectType` bindings           |
| 10–19   | `Questionnaire.code` (LOINC / SNOMED / custom)|
| 20–29   | `item.code`                                   |
| 30–49   | `answerOption.valueCoding`                    |
| 50–59   | `initial` Coding / Quantity (UCUM often local)|
| 60–69   | `enableWhen.answerCoding` / Quantity          |
| 70–79   | `jurisdiction` / `useContext`                 |
| 80–89   | `meta.security` / `meta.tag`                  |
| 90–99   | `answerValueSet` + coded initial / unresolved |
| 100–119 | UV/core Questionnaire extensions (coded)      |
| 120–139 | Mock transport / policy modes                 |
| 190–199 | Multi-error combos                            |

## Mock modes (`expectations.tsv` col 4)

| Mode                            | Behaviour                                                |
|---------------------------------|----------------------------------------------------------|
| `DEFAULT`                       | Support all; fail bad codes / wrong displays             |
| `EMPTY_SEARCH`                  | CodeSystem/ValueSet search empty (remote unsupported)    |
| `VALIDATE_HTTP_404` / `400` / `500` | `$validate-code` returns that HTTP status            |
| `VALIDATE_MALFORMED_PARAMETERS` | 200 Parameters without `result`                          |
| `REJECT_ALL_CODES`              | Every `$validate-code` → `result=false`                  |
| `ACCEPT_ALL_CODES`              | Every `$validate-code` → `result=true` (negative control)|

Sentinel substrings (special assertions, not literal issue text):

| Sentinel | Meaning |
|----------|---------|
| `ACCEPT_ALL_SENTINEL` | `ACCEPT_ALL_CODES` must **not** emit a remote failure for `0000-0` |
| `EMPTY_SEARCH_NO_REMOTE` | `EMPTY_SEARCH` must **not** emit `The Remote Terminology server` wrappers |
| `UNRESOLVED_VS_SILENT` | A dangling `answerValueSet` canonical alone is silent (no remote wrapper) |

## Why some substrings are not `The Remote Terminology server`

- **UCUM** (`http://unitsofmeasure.org`) is handled by
  `CommonCodeSystemsTerminologyService` **before** remote in the production chain, so
  local messages win even when a remote URL is configured.
- **Required / in-package bindings** (e.g. some FHIR core ValueSets, THO fragments) may
  produce local `Unknown code` / binding text without a remote wrapper.
- **Missing ValueSet canonicals** may warn about expansion / resolution without calling
  `$validate-code`.

Those fixtures still belong here because they are terminology surfaces authors hit while
remote Tx is selected; the README documents when local wins.

## Sources used to pick cases

- R4 Questionnaire element bindings (`status`, `subjectType`, `jurisdiction`, `code`,
  `item.code`, `answerOption`/`initial` Codings, `enableWhen.answerCoding`, …) from
  `fhir-questionnaire-specification.md` / detailed descriptions
- UV Extensions coded elements (`itemControl`, `usageMode`, `mimeType`, `questionnaire-unit`,
  `versionAlgorithm`, `answerConstraint`, `referenceResource`, …)
- DomainResource `meta.security` / `meta.tag`
- HAPI remote Tx error wrapping + HTTP failure paths
- Prior scratch probe `TerminologyProbeScratchTest`

## Quirks (HAPI 8.8.1)

- Nested `ValidationSupportChain` prefers the **first** support that both
  `isCodeSystemSupported` and returns a non-null `validateCode` result — remote is last.
- Display mismatches are only remoted when the mock (or real Tx) sees the `display`
  parameter on `$validate-code`. Many binding paths never send `display` remotely.
- `VALIDATE_MALFORMED_PARAMETERS` / `VALIDATE_HTTP_500` may throw rather than return a
  `ValidateResponse`; the test accepts matching exceptions.
- **Mock pitfall:** if `ValueSet?url=http://loinc.org` returns a ValueSet, HAPI reports
  "The Coding references a value set, not a code system" and never calls
  `CodeSystem/$validate-code`. The mock only advertises ValueSets for VS-shaped
  canonicals (and treats `does-not-exist` / `missing` / `bad-units` as unresolved).
- A dangling `answerValueSet` canonical with no coded answer is often **silent**
  (see `94`/`95` + `UNRESOLVED_VS_SILENT`).
- Mime-type / some core coded extensions are validated locally (or not at all); several
  extension fixtures therefore also plant a bad LOINC `item.code` to force a remote path.
