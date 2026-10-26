# R4 Questionnaire live validator

Spring Boot application that validates **FHIR R4 `Questionnaire`** JSON using HAPI FHIR (`FhirInstanceValidator` + `ValidationSupportChain` with `DefaultProfileValidationSupport`, `InMemoryTerminologyServerValidationSupport`, `CommonCodeSystemsTerminologyService`, and optional `RemoteTerminologyServiceValidationSupport`). After instance validation it also runs **authoring-time FHIRPath checks** on embedded `text/fhirpath` (and implied-FHIRPath strings): `IFhirPath.parse`, variable self-reference, unknown `linkId` literals, undeclared `%` variables. `text/cql` and `application/x-fhir-query` are reported as informational skips, not compiled.

Pulls **published** HAPI artifacts from Maven Central — no full HAPI source build required. Set `hapi.fhir.version` in `pom.xml` to pin a release.

## Run

```bash
mvn spring-boot:run
```

Open `http://localhost:8080/`.

Remote terminology is **opt-in** from the UI (default: local only). Presets and allowed hosts are configured in `application.properties` (`validator.terminology.*`).

## API

`GET /api/terminology-servers` — preset servers (`id`, `label`, `url`).

`POST /api/validate-questionnaire?terminologyServer=none|tx-fhir-org-r4&terminologyUrl=`

- **Body:** raw JSON for a `Questionnaire` resource (`Content-Type: application/json`).
- **Query:** `terminologyServer` (preset id) and/or `terminologyUrl` (custom https URL on an allowlisted host). Omit both / use `none` for local-only.
- **Response:** `{ "ok": boolean, "issues": [ { "severity", "message", "line", "column", "location" } ] }`

## UI

Monaco editor (CDN) with debounced validation, terminology server picker, `AbortController` cancellation, client-side `JSON.parse` for fast syntax errors, optional “validate on blur”, and a 2 MiB client-side size guard (server also limits Tomcat post size).

## Tests

```bash
mvn test
```
