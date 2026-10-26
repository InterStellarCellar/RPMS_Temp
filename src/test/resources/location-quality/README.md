# location-quality corpus

Classpath fixtures for `LocationQualityCorpusTest`.

These Questionnaires **do** produce validation issues. The suite asserts **where** those
issues are reported (`location` FHIRPath-like string, and `line`/`column`), documenting
both accurate enrichment and known coarse HAPI line/column behavior on pretty-printed JSON.

## Expectations

`expectations.tsv` columns:

| column | meaning |
|--------|---------|
| filename | fixture under this directory |
| messageSubstring | at least `minIssues` issues must contain this |
| minIssues | minimum matching issues |
| locationSubstring | each matching issue's `location` must contain this (`-` to skip) |
| lineMode | `exact` \| `min` \| `coarse1` |
| lineArg | mode-specific number |

### lineMode

| mode | assertion |
|------|-----------|
| `exact` | `line == lineArg` |
| `min` | `line >= lineArg` (accurate / enriched positions on pretty JSON) |
| `coarse1` | `line == 1` **and** `column > lineArg` — HAPI often reports a compact-JSON byte offset as column on line 1 even when the file is pretty-printed |

Multiple TSV rows may share a filename (multi-issue fixtures).

Uses the production package-loaded chain (`ValidationBeansConfig`).

## Numbering

| Range | Category | Quality |
|-------|----------|---------|
| 00–09 | JSON syntax / FHIR parse unknown elements | **Accurate** (`JsonSyntaxChecker`, `JsonElementLocator`) |
| 10–19 | `que-2` duplicate `linkId` | **Accurate** (`InvariantLocationEnricher` expands to each duplicate) |
| 20–39 | Core / structural / datatype / narrative / enableWhen | **Coarse line** (`coarse1`) but FHIRPath `location` is usually useful |
| 50–59 | Mixed | One payload with both enriched and coarse issues; parse short-circuit |

## Why coarse1 exists

HAPI `FhirInstanceValidator` frequently emits `line=1` with a large `column` for
instance-validation messages against pretty JSON. The UI partially compensates by resolving
`location` against the editor text (`app.js`). This corpus **locks in** the current backend
behavior so:

1. enricher regressions (que-2 falling back to `1:N`) fail tests
2. accidental changes to coarse HAPI reporting are noticed
3. future location-enrichment work has a clear before/after matrix

## Pipeline note

`51-multi-parse-unknown-and-que2.json` only asserts the **parse** issue. FHIR parse errors
short-circuit before instance validation, so `que-2` never runs — that is intentional
documentation of stage ordering.

## Related

- Unit tests: `InvariantLocationEnricherTest`, `JsonSyntaxCheckerTest`
- Failure corpora own *whether* the issue fires; this corpus owns *where* it is reported
