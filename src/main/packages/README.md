# FHIR NPM packages

These `.tgz` implementation-guide packages are loaded onto the validation chain at startup
(`ValidationBeansConfig`, classpath pattern `packages/*.tgz`). The pom copies this folder to
`classpath:packages/`. Without them, SDC profile and extension validation fails and about
108 corpus tests break.

| Package | Version | Why |
|---|---|---|
| `hl7.fhir.uv.sdc` | 4.0.0 | SDC Questionnaire profiles and invariants (sdc-*, cnl-*, que-14/15) |
| `hl7.fhir.uv.extensions.r4` | 5.3.0-ballot-tc1 | Exact dependency pinned by SDC 4.0.0; a different version (e.g. 5.3.0) breaks versioned slice discriminators (`preferredTerminologyServer|5.3.0-ballot-tc1`) |
| `hl7.terminology.r4` | 7.1.0 | THO code systems and value sets; version pinned by SDC 4.0.0 |
| `hl7.fhir.uv.xver-r5.r4` | 0.1.0 | Cross-version extensions (R5 elements back-ported to R4); R5 core CodeSystems are filtered out |

Re-download with:

```sh
cd src/main/packages
for p in hl7.fhir.uv.sdc/4.0.0 hl7.fhir.uv.extensions.r4/5.3.0-ballot-tc1 hl7.terminology.r4/7.1.0 hl7.fhir.uv.xver-r5.r4/0.1.0; do
  curl -sL -o "${p%/*}-${p#*/}.tgz" "https://packages.fhir.org/$p"
done
```

Versions are the `dependencies` block of SDC 4.0.0's `package.json`. Two declared dependencies
are deliberately omitted: `hl7.fhir.uv.tools.r4` (IG-publisher tooling extensions, not needed to
validate a Questionnaire) and `hl7.fhir.r4.examples`. Transitive declarations that disagree
(`xver-r5.r4` and THO both ask for `extensions.r4` 5.2.0) are overridden by the rule below; the
corpus tests pass with this set.

Keep exactly one version of each package here. Two versions of the same package put two
StructureDefinitions under one canonical URL and the validator's versioned lookups start failing.
After changing packages, run `mvn clean` (or delete `target/`) before running tests; stale copies
in `target/classes/packages/` are still on the classpath.
