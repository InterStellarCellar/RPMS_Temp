# R4 Questionnaire live validator

Spring Boot application that validates **FHIR R4 `Questionnaire`** JSON using HAPI FHIR (`FhirInstanceValidator` + `ValidationSupportChain` with `DefaultProfileValidationSupport`, `InMemoryTerminologyServerValidationSupport`, `CommonCodeSystemsTerminologyService`, and optional `RemoteTerminologyServiceValidationSupport`). After instance validation it also runs **authoring-time FHIRPath checks** on embedded `text/fhirpath` (and implied-FHIRPath strings): `IFhirPath.parse`, variable self-reference, unknown `linkId` literals, undeclared `%` variables. `text/cql`, `application/x-fhir-query` and SDC-defined functions (`weight()`, `ordinal()`, `answers()`, `sum()`) are reported as informational skips, not compiled. A further authoring pass (`CodingTrendabilityValidator`) requires a stable `item.code` on value-producing questions when monitoring intent is present (error with observation extract / link-period or scoring `calculatedExpression`, warning with `url`+`version` alone), rejects example / incomplete Codings when present, and requires `ordinalValue` / `itemWeight` on choice options referenced by a scoring `calculatedExpression`.

Pulls **published** HAPI artifacts from Maven Central — no full HAPI source build required. Set `hapi.fhir.version` in `pom.xml` to pin a release (currently 8.8.1, Java 21).

## FHIR packages

SDC 4.0.0 and its declared dependencies live in `src/main/packages/*.tgz` and are copied to `classpath:packages/` at build time. Versions are pinned; see `src/main/packages/README.md` before changing them. Delete `target/` after changing packages, stale copies stay on the classpath.

## Run

```bash
mvn spring-boot:run
```

Open `http://localhost:8080/`.

Remote terminology is **opt-in** from the UI (default: local only). Presets and allowed hosts are configured in `application.properties` (`validator.terminology.*`). One validator is cached per terminology target.

## API

`GET /api/terminology-servers` — preset servers (`id`, `label`, `url`).

`POST /api/validate-questionnaire?terminologyServer=none|tx-fhir-org-r4&terminologyUrl=`

- **Body:** raw JSON for a `Questionnaire` resource (`Content-Type: application/json`).
- **Query:** `terminologyServer` (preset id) and/or `terminologyUrl` (custom https URL on an allowlisted host). Omit both / use `none` for local-only.
- **Response:** `{ "ok": boolean, "issues": [ { "severity", "message", "line", "column", "location" } ] }`

## UI

Monaco editor (CDN) with debounced validation, terminology server picker, `AbortController` cancellation, client-side `JSON.parse` for fast syntax errors, optional “validate on blur”, and a 2 MiB client-side size guard.

## Tests

```bash
mvn test
```

Corpus tests: each directory under `src/test/resources/` holds fixtures plus an `expectations.tsv`. `valid/` is the negative control, `known-gaps/` lists flaws the validator deliberately accepts.

## Evaluation

```bash
mvn test -Dtest=EvaluationReportTest -Devaluation=true -Dsurefire.failIfNoSpecifiedTests=false
```

Replays every fixture and the 34 SDC 4.0.0 example Questionnaires, writes `target/evaluation-report.md` with coverage per category, false positives and latency.
