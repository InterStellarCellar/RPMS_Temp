# known-gaps corpus

Classpath fixtures for `KnownGapsCorpusTest`.

These Questionnaires are **intentionally flawed** from an authoring / runtime perspective,
but the current validator (HAPI 8.8.1 + local packages, no remote Tx) does **not** emit
`error` / `fatal` issues for the documented gap.

Use this suite to:

- document what Questionnaire validation does **not** catch
- prevent silent “fixes” that start failing previously accepted authoring smells without review
- explain surprising `ok: true` results in the UI

## Expectations

`expectations.tsv` columns:

| column | meaning |
|--------|---------|
| filename | fixture under this directory |
| max_error_fatal | usually `0` — allowed count of `error`/`fatal` |
| gapKey | category key (see below) |

Warnings and information messages are allowed. JSON must be well-formed and FHIR parse must
be clean (failures of those belong in `json-malformed/` / `fhir-parse-malformed/`).

Uses the production package-loaded chain (`ValidationBeansConfig`) so SDC / UV Extensions /
terminology packages match the webapp.

## Numbering / gapKey

| Range | gapKey | What is *not* checked |
|-------|--------|------------------------|
| 00–09 | `expression-not-compiled` | FHIRPath / CQL / x-fhir-query in Expression extensions is not compiled or executed |
| 10–19 | `type-consistency-unchecked` | `answerOption` / `initial` / `enableWhen.answer[x]` type vs `item.type` (or referenced question) |
| 20–29 | `extension-semantics-unchecked` | Extension present and typed OK, but meaningless (inverted min/max, invalid regex, wrong itemControl, …) |
| 30–39 | `unresolved-reference` | Missing ValueSet / definition / Library / derivedFrom / fragment `#id` — no hard error |
| 40–49 | `authoring-soft-rule` | Spec SHOULD / HAPI-dropped authoring rules (`sdc-rend-1`, prefix dupes, circular enableWhen, …) |
| 50–59 | `sdc-runtime-not-executed` | Populate / extract / launch / assemble semantics not run at validate time |
| 60–69 | `authoring-soft-rule` | More soft smells (opaque linkIds, deep nesting, example bindings, …) |
| 70–79 | `expression-or-sdc-gap` | Expressions that reference missing linkIds; query languages not executed |
| 80–95 | `combo-gap` / soft rules | Combinations of independent gaps still pass |

## Sources

- R4 Questionnaire detailed descriptions (`fhir-questionnaire-specification.md` / HL7 definitions):
  comments that answer/initial types “must agree” with `item.type` — not enforced here as errors
- SDC STU4 expression / populate / extract / behave / render profiles
- UV Extensions Questionnaire extensions (`minValue`, `maxValue`, `regex`, `mimeType`, …)
- Prior notes in `sdc/README.md` and `instance-structural/README.md` (dropped `sdc-rend-1`, `dom-2`, …)
- Manual probes under `dont-commit/examples/` (`6-expression-fhirpath.json`, `min-max-extension.json`)

## Explicitly *not* gaps (removed after probing)

Fixtures that **do** produce `error`/`fatal` were deleted rather than weakened, including:

- empty string primitives (`HAPI-1821` attribute must not be empty)
- `answerOption` on `boolean` (fires `que-5` — belongs in `que-invariant/`)
- `enableWhen` + `enableWhenExpression` together (fires `sdc-behave-2` — belongs in `sdc/`)
- unknown `questionnaire-referenceResource` code (fires Unknown code)
- wrong Expression.language code binding (fires Unknown code)

## Related corpora

| Want | Use |
|------|-----|
| Must pass | `valid/` |
| Must fail JSON | `json-malformed/` |
| Must fail FHIR parse | `fhir-parse-malformed/` |
| Must fail `que-*` | `que-invariant/` |
| Must fail other instance rules | `instance-structural/` |
| Must fail SDC profile rules | `sdc/` |
| Location line/col quality | `location-quality/` |
