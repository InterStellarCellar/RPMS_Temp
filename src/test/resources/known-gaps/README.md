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
| 09 | `expression-not-compiled` | CQL (`text/cql`) in Expression extensions is not compiled or executed. **`text/fhirpath` syntax is checked** — see `expression-fhirpath/` |
| 20–29 | `extension-semantics-unchecked` | Extension present and typed OK, but meaningless (never-matching regex, wrong itemControl, …). **Invalid regex syntax, min/max order, slider, hidden+required, answerValueSet resolution** are now enforced — see `range-unit-safety/` |
| 30–39 | `unresolved-reference` | Missing definition / Library / derivedFrom / Tx — no hard error. **`unitValueSet` / `answerValueSet` resolution are now errors** — see `range-unit-safety/` |
| 40–49 | `authoring-soft-rule` | Spec SHOULD / HAPI-dropped authoring rules (`sdc-rend-1`, prefix dupes, …). **Circular / display / impossible `enableWhen`** — see `monitoring-expression-ux/` |
| 50–59 | `sdc-runtime-not-executed` | Populate / extract / launch / assemble semantics not run at validate time |
| 60–69 | `authoring-soft-rule` | More soft smells (opaque linkIds, deep nesting, …). **`item.code` example systems are now errors** — see `coding-trendability/`. **Bare `readOnly` without value source is now a warning** — see `monitoring-expression-ux/` |
| 70–79 | `expression-or-sdc-gap` | Query languages (`application/x-fhir-query`) not executed; leftover SDC authoring smells |
| 80–95 | `combo-gap` / soft rules | Combinations of independent gaps still pass. **code-only `answerOption.valueCoding` is now an error** — see `coding-trendability/`. **`83` reduced to prefix-dup only** (circular enableWhen is now an error) |

## Sources

- R4 Questionnaire detailed descriptions (`fhir-questionnaire-specification.md` / HL7 definitions)
- SDC STU4 expression / populate / extract / behave / render profiles
- UV Extensions Questionnaire extensions (`minValue`, `maxValue`, `regex`, `mimeType`, …)
- Prior notes in `sdc/README.md` and `instance-structural/README.md` (dropped `sdc-rend-1`, `dom-2`, …)
- Manual probes under `dont-commit/examples/` (`min-max-extension.json`)
- Invalid `text/fhirpath` fixtures moved to `expression-fhirpath/` once authoring-time parse landed
- `49-observationLinkPeriod-without-code` moved to `observation-extract/` once linkPeriod⇒code landed
- `20` / `27` / `34` / `86` / `91` moved to `range-unit-safety/` (min/max, hidden+required, unitValueSet, slider)
- `10–19` / `21` / `42` / `85` moved to `type-consistency/` (answer/initial/enableWhen/minValue types, initialSelected)
- `22` / `30` / `37` / `81` / `82` moved to `range-unit-safety/` (regex syntax, answerValueSet)
- `43` / `95` moved to `monitoring-expression-ux/` (enableWhen→display / impossible all)
- `69` / `90` moved to `coding-trendability/` (example CodeSystem / coding without system)
- `44` / `65` moved to `monitoring-expression-ux/` (circular enableWhen / readOnly without value source)
- `83` reduced to prefix-dup only (circular enableWhen is now an error)
- `70-isSubject-true-without-subjectType` moved to `subject-identity/` once isSubject⇒subjectType landed
- `67` empty `subjectType` remains here (`max_error_fatal=0`); rule 19 now warns that Patient is missing

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
| Must fail embedded FHIRPath | `expression-fhirpath/` |
| Must fail observation extract / populate authoring | `observation-extract/` |
| Must fail range / unit / clinical-safety authoring | `range-unit-safety/` |
| Must fail type agreement authoring | `type-consistency/` |
| Must fail coding / terminology for trendability | `coding-trendability/` |
| Must fail monitoring expression UX authoring | `monitoring-expression-ux/` |
| Must fail subject / identity / longitudinal authoring | `subject-identity/` |
| Location line/col quality | `location-quality/` |
