# sdc corpus

Classpath fixtures for `SdcCorpusTest`.

- `*.json` — valid JSON, clean FHIR parse, and an SDC `meta.profile`; triggers
  **SDC profile** instance-validation issues via the production package-loaded chain
- `expectations.tsv` — filename, minimum matching issue count, required message substring

These are **not** JSON syntax errors (`json-malformed/`), **not** FHIR parse errors
(`fhir-parse-malformed/`), **not** core-only structural misses (`instance-structural/`),
and **not** core `que-0`…`que-13` alone (`que-invariant/`).

The test harness uses `ValidationBeansConfig.localValidationSupportChain` so
`hl7.fhir.uv.sdc.r4` (plus extensions / terminology / xver) is loaded.

## Numbering

| Range   | Category                       | Typical assertion                                                 |
| ------- | ------------------------------ | ----------------------------------------------------------------- |
| 00–09   | Base / search cardinality      | missing `url`, `title`, `date`                                    |
| 10–19   | Common constraints             | `sdc-2`, `sdc-3`, `cnl-*`, `que-15`, `que-1b`, `que-1c`, `que-14` |
| 20–29   | `sdc-1` answerExpression clash | answerExpression + option/ValueSet                                |
| 30–39   | `sdc-base-*` answerOption      | empty Coding / code w/o system / empty Ref                        |
| 40–49   | Behave                         | `sdc-behave-1/2/3`                                                |
| 50–59   | Render                         | `sdc-rend-2` (see dropped notes for rend-1)                       |
| 60–69   | Populate                       | `sdc-pop-1`, `sdc-pop-2`                                          |
| 70–79   | Extract                        | `sdc-obsn-1`, `tev-1`, missing contained                          |
| 80–89   | Modular / adaptive             | assemble-expectation, adapt rules                                 |
| 90–99   | Extension type / inv           | `sdc-inv1`, `sdc-lcext-1`, wrong types                            |
| 190–199 | Multi-error combos             | multi-row TSV                                                     |

## Multi-error TSV convention

Combo fixtures may appear on multiple `expectations.tsv` rows (one substring each).

## Dropped / unverifiable on HAPI 8.8.1

- **sdc-rend-1** (page itemControl only at root): nested-page fixtures did not fire under
  this validator build; **sdc-rend-2** (mixed root page/non-page) does fire and is covered.
- Bare empty `valueReference: {}` did not retain enough structure for **sdc-base-4**; the
  corpus uses `valueReference: { "type": "Patient" }` instead.
- **que-1a** (`status='complete'` + empty group): R4 `Questionnaire.status` is
  `PublicationStatus` (`draft|active|retired|unknown`) — `"complete"` fails FHIR parse, so
  the constraint cannot fire on a parse-clean fixture. **que-1b** / **que-1c** are covered.
- **dom-2** HAPI flattens nested contained → only dom-3 fires.
- **dom-4/dom-5** HAPI strips versionId / lastUpdated / security from contained meta before constraints run, so those keys never appear.
