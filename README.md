# R4 Questionnaire live validator

Spring Boot application that validates **FHIR R4 `Questionnaire`** JSON using HAPI FHIR (`FhirInstanceValidator` + `ValidationSupportChain` with `DefaultProfileValidationSupport` and `InMemoryTerminologyServerValidationSupport`), with optional **XSD** and **Schematron** toggles.

Pulls **published** HAPI artifacts from Maven Central — no full HAPI source build required. Set `hapi.fhir.version` in `pom.xml` to pin a release.

## Run

```bash
mvn spring-boot:run
```

Open `http://localhost:8080/`.

## API

`POST /api/validate-questionnaire?xsd=false&schematron=false`

- **Body:** raw JSON for a `Questionnaire` resource (`Content-Type: application/json`).
- **Response:** `{ "ok": boolean, "issues": [ { "severity", "message", "line", "column", "location" } ] }`

## UI

Monaco editor (CDN) with debounced validation, `AbortController` cancellation, client-side `JSON.parse` for fast syntax errors, optional “validate on blur”, and a 2 MiB client-side size guard (server also limits Tomcat post size).

## Tests

```bash
mvn test
```
