# valid corpus

Classpath fixtures for `ValidCorpusTest`.

- `*.json` — Questionnaires that should **pass** validation (JSON + FHIR parse + instance)
- `expectations.tsv` — filename, max allowed `error`/`fatal` count (normally `0`), short note

Warnings and information messages are allowed (e.g. `que-0`, `dom-6`, SDC best-practice
advice). Failures belong in the other corpora.

Uses the production package-loaded chain (`ValidationBeansConfig`) so SDC / UV Extensions /
terminology / xver packages match the webapp.

## Numbering

| Range   | Category                         | Source                                      |
|---------|----------------------------------|---------------------------------------------|
| 00–09   | Minimal / root metadata          | synthetic                                   |
| 10–29   | Item types                       | synthetic                                   |
| 30–49   | answerOption / initial / flags   | synthetic                                   |
| 50–69   | enableWhen / enableBehavior      | synthetic                                   |
| 70–89   | Nesting / display / contained    | synthetic                                   |
| 90–109  | UV Questionnaire extensions      | synthetic + `dont-commit` min-max           |
| 110–129 | SDC profile-conformant           | synthetic + fixed `sdc-questionnaire.json`  |
| 200+    | External R4 Questionnaires       | `dont-commit/examples/ version4 Fhir questionnaires/` |

## External imports

Files `200-ext-*.json` are copied from the local `version4 Fhir questionnaires` folder
(CIRG clinical forms, HL7 examples, GAD-7, HPAI, CQF example). Filenames are sanitized;
payloads are otherwise unchanged so they exercise real-world authoring patterns.

If an external fixture fails with `error`/`fatal` under HAPI 8.8.1 + the current packages,
document it under **Dropped / known failures** below and remove it from `expectations.tsv`
(or move it to a failure corpus) rather than weakening the suite.

## Multi-row TSV

This corpus uses one row per file. `max_error_fatal` is almost always `0`.

## Quirks observed on HAPI 8.8.1

- **que-12 with exactly 2 `enableWhen`:** despite the published R4 FHIRPath
  (`enableWhen.count() > 2`), this validator build reports
  `Constraint failed: que-12` when there are **two** `enableWhen` entries and no
  `enableBehavior`. A “two enableWhen without behavior” fixture is therefore
  **not** valid here; use `enableBehavior` whenever `enableWhen` count ≥ 2.
  Coverage for quantity-based enableWhen lives in `56-enableWhen-answerQuantity.json`.

## Dropped / known failures

None — all `200-ext-*` imports from `version4 Fhir questionnaires` and all
synthetic fixtures currently pass with `ok=true` and zero `error`/`fatal`
(warnings such as `que-0` / `dom-6` / missing narrative are allowed).
