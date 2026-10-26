# instance-structural corpus

Classpath fixtures for `InstanceStructuralCorpusTest`.

- `*.json` — valid JSON **and** clean FHIR parse (zero `CollectingParserErrorHandler` errors);
  triggers **instance-validation** issues via `FhirInstanceValidator` that are **not** `que-*`
  invariants
- `expectations.tsv` — filename, minimum matching issue count, required message substring

These are **not** JSON syntax errors (see `json-malformed/`), **not** FHIR parse errors
(see `fhir-parse-malformed/`), and **not** `que-0`…`que-13` invariants (see `que-invariant/`).

When adding a fixture, update `expectations.tsv`.

## Numbering

| Range   | Category                          | Description                                           |
|---------|-----------------------------------|-------------------------------------------------------|
| 00–09   | Root required / null              | Missing or null `status`                              |
| 10–19   | Item required                     | Missing `linkId`, `type`, or both                     |
| 20–39   | enableWhen structure + link       | Missing `question`/`operator`/`answer[x]`, bad linkId |
| 40–49   | answerOption / initial value[x]   | Backbone present but missing required `value[x]`      |
| 50–59   | Narrative                         | Missing `text.status`/`div`, `txt-1`, `txt-2`         |
| 60–69   | DomainResource                    | `dom-3`, `dom-6` (see dropped notes for `dom-2/4/5`)  |
| 70–89   | Nested datatype invariants        | `per-1`, `qty-3`, `att-1`, `cpt-2`, `rng-2`, etc.    |
| 90–99   | Post-parse format / binding       | Invalid id, non-absolute canonical, `subjectType`     |
| 190–199 | Multi-error combos                | Multiple independent structural failures              |

## Multi-error TSV convention

For combo fixtures, `expectations.tsv` has one row per expected message substring against
the same filename. Duplicate filenames are allowed.

## Key HAPI 8.8.1 quirks

- Omitting a required primitive (or setting it to JSON `null`) fires at **instance** validation
  with `minimum required = 1`. Setting it to empty string `""` fires at **parse** — that belongs
  in `fhir-parse-malformed/`, not here.
- Bare `"answerOption":[{}]` / `"initial":[{}]` often does **not** trigger a `value[x]` min
  error. Adding another backbone field (e.g. `initialSelected`, `id`) forces the component to be
  non-empty and the min constraint to fire.
- Most fixtures include a minimal valid `text` so `dom-6` ("should have narrative") does not
  flood the issue list; `60-dom-6-no-narrative.json` is the dedicated fixture for that warning.

## Dropped / unverifiable on HAPI 8.8.1

- **dom-2** (no nested `contained`): HAPI flattens nested contained resources into the root
  `contained` list, so only **dom-3** fires — never `dom-2`.
- **dom-4** / **dom-5** (`contained.meta.versionId|lastUpdated|security`): HAPI strips those
  meta fields from contained resources before constraint evaluation, so the keys never appear;
  a meta that becomes empty after stripping may report "Object must have some content" instead.
