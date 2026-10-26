# profile-canonical corpus

Classpath fixtures for `ProfileCanonicalCorpusTest`.

- `*.json` — valid JSON and clean FHIR parse; triggers **profile resolution /
  application** or **canonical-reference** issues via the production package-loaded
  chain (`ValidationBeansConfig.localValidationSupportChain`)
- `expectations.tsv` — filename, minimum matching issue count, required message substring

These are **not** JSON syntax errors (`json-malformed/`), **not** FHIR parse errors
(`fhir-parse-malformed/`), **not** core `que-0`…`que-13` alone (`que-invariant/`),
**not** general structural misses (`instance-structural/`), and **not** SDC invariant
hunting for its own sake (`sdc/` — though a few fixtures *apply* SDC profiles to prove
`meta.profile` resolution works).

When adding a fixture, update `expectations.tsv`.

## Numbering

| Range   | Category                                   | Typical assertion                                      |
| ------- | ------------------------------------------ | ------------------------------------------------------ |
| 00–19   | `meta.profile` unresolved / unknown        | `Profile reference` / `Invalid profile`                |
| 20–39   | `meta.profile` wrong resource type         | `Specified profile type was '…'`                       |
| 40–59   | `meta.profile` format                      | whitespace, empty, fragment                            |
| 60–79   | Profile application / conflicts            | SDC profile applied from `meta.profile`                |
| 80–99   | `derivedFrom` canonical                    | absolute / whitespace / unresolved warning             |
| 100–119 | `url` / `answerValueSet` / `definition`    | absolute / whitespace                                  |
| 120–139 | `extension.url` / `valueCanonical`         | absolute extension URL; relative canonical values      |
| 160–169 | `Identifier.system`                        | must be absolute                                       |
| 190–199 | Multi-error combos                         | multi-row TSV                                          |

## Multi-error TSV convention

Combo fixtures may appear on multiple `expectations.tsv` rows (one substring each).

## Key HAPI 8.8.1 quirks

- Declaring a **known** core `StructureDefinition/Questionnaire` profile is a no-op
  (no issues). Unknown / typo / wrong-version / wrong-type profiles do fire.
- `derivedFrom` pointing at an absolute but missing Questionnaire yields a
  **warning** (`…referred to in the derivation could not be found`), not an error.
  Fragment `#id` references are also treated as unresolved warnings — even when a
  contained Questionnaire with that id exists.
- Absolute but **unresolved** `answerValueSet` / `item.definition` /
  `valueCanonical` (e.g. `cqf-library`) generally produce **no** instance issues
  under local-only validation. Format problems (relative, whitespace, `|version`
  alone) do fire.
- `derivedFrom` set to a resolvable **ValueSet** canonical can throw
  `ClassCastException` inside HAPI (ValueSet cast to Questionnaire). That case is
  **excluded** from this corpus — see dropped notes.
- Relative `meta.profile` (e.g. `"Questionnaire"`) is already covered in
  `instance-structural/94-meta-profile-not-absolute.json`; this corpus focuses on
  absolute-but-bad and type-mismatch profiles.
- Empty `meta.profile` `""` and invalid contained fragment `Reference`s (`#missing`)
  surface as **FHIR parse** errors (`HAPI-1821` / `HAPI-1826`) under
  `CollectingParserErrorHandler` — they belong in `fhir-parse-malformed/`, not here.
- A **wrong-type** `meta.profile` (e.g. Patient on Questionnaire) tends to short-circuit:
  only the type-mismatch error is reported; sibling format issues on the same instance
  often do not appear. Multi fixtures therefore pair *unknown* profiles (not type
  mismatches) with other canonical problems.

## Dropped / unverifiable on HAPI 8.8.1

- **Unresolved absolute `answerValueSet`** (including `#missing`, CodeSystem URL,
  versioned missing): no issue under local terminology support.
- **Unresolved absolute `item.definition`** (with or without `#fragment`): no issue.
- **Unresolved absolute extension `valueCanonical`** (`cqf-library`,
  `targetStructureMap`, `unitValueSet`, `subQuestionnaire`): no issue when the
  canonical is absolute and well-formed.
- **`derivedFrom` → ValueSet URL**: HAPI throws `ClassCastException` during
  validation — not a stable assertion surface.
- **Duplicate identical `meta.profile` entries**: no issue.
- **`Questionnaire.url` with `|` / `#` / `urn:` / `ftp:`**: no core issue without an
  SDC/`cnl-1` profile (see `sdc/` for `cnl-1`).
- **`meta.source` / `implicitRules` relative**: no issue observed.
- **Relative `Reference`** on `answerOption` / `initial` / `enableWhen` (e.g.
  `Patient/1`): no reference-resolution issue for Questionnaire authoring (and
  `answerOption` on `type=reference` hits `que-5` instead — belongs in
  `que-invariant/`).
- **Empty `meta.profile` / invalid `#` References**: parse-layer (`HAPI-1821` /
  `HAPI-1826`); keep out of this instance corpus.
