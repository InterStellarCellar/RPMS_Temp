package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.hl7.fhir.r4.model.Questionnaire;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationExtractValidatorTest {

	private static FhirContext ourCtx;

	@BeforeAll
	static void init() {
		ourCtx = FhirContext.forR4();
	}

	@Test
	void extractWithoutCode_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Q",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				          "valueBoolean": true
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "observationExtract requires item.code"), describe(issues));
		assertTrue(issues.stream().anyMatch(i -> i.getLine() != null && i.getLine() > 1), describe(issues));
	}

	@Test
	void linkPeriodWithoutCode_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Q",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationLinkPeriod",
				          "valueDuration": { "value": 1, "system": "http://unitsofmeasure.org", "code": "a" }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "observationLinkPeriod requires item.code"), describe(issues));
	}

	@Test
	void extractableDecimalWithoutUnit_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "weight",
				      "type": "decimal",
				      "text": "Weight",
				      "code": [ { "system": "http://loinc.org", "code": "29463-7" } ],
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				          "valueBoolean": true
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "extractable numeric item requires a unit"), describe(issues));
	}

	@Test
	void unitOptionOnString_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Q",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-unitOption",
				          "valueCoding": { "system": "http://unitsofmeasure.org", "code": "kg" }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "unitOption is only valid on quantity"), describe(issues));
	}

	@Test
	void unitValueSetOnInteger_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "integer",
				      "text": "Q",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-unitValueSet",
				          "valueCanonical": "http://example.org/ValueSet/units"
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "unitValueSet is only valid on quantity"), describe(issues));
	}

	@Test
	void extractWithoutCategory_isWarning() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Q",
				      "code": [ { "system": "http://loinc.org", "code": "1234-5" } ],
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				          "valueBoolean": true
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverity(issues, "error"), describe(issues));
		assertTrue(hasSeverityMessage(issues, "warning", "observationExtractCategory is recommended"), describe(issues));
	}

	@Test
	void panelChildSharesParentCode_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "panel",
				      "type": "group",
				      "text": "Vitals",
				      "code": [ { "system": "http://loinc.org", "code": "85353-1" } ],
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				          "valueBoolean": true
				        }
				      ],
				      "item": [
				        {
				          "linkId": "weight",
				          "type": "quantity",
				          "text": "Weight",
				          "code": [ { "system": "http://loinc.org", "code": "85353-1" } ],
				          "extension": [
				            {
				              "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				              "valueCode": "component"
				            },
				            {
				              "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-unit",
				              "valueCoding": { "system": "http://unitsofmeasure.org", "code": "kg" }
				            }
				          ]
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(
				hasSeverityMessage(issues, "error", "observation extract component/member requires distinct codes"),
				describe(issues));
	}

	@Test
	void inheritedExtractWithoutCode_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "extension": [
				    {
				      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				      "valueBoolean": true
				    }
				  ],
				  "item": [
				    { "linkId": "q1", "type": "string", "text": "Q" }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "observationExtract requires item.code"), describe(issues));
	}

	@Test
	void childOptOut_skipsInheritedExtractRules() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "extension": [
				    {
				      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				      "valueBoolean": true
				    },
				    {
				      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtractCategory",
				      "valueCodeableConcept": {
				        "coding": [ { "system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "survey" } ]
				      }
				    }
				  ],
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Q",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				          "valueBoolean": false
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverity(issues, "error"), describe(issues));
		assertFalse(hasSeverityMessage(issues, "warning", "observationExtractCategory"), describe(issues));
	}

	@Test
	void happyPath_extractCodeUnitCategory_isClean() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "extension": [
				    {
				      "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtractCategory",
				      "valueCodeableConcept": {
				        "coding": [ { "system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs" } ]
				      }
				    }
				  ],
				  "item": [
				    {
				      "linkId": "weight",
				      "type": "quantity",
				      "text": "Weight",
				      "code": [ { "system": "http://loinc.org", "code": "29463-7" } ],
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				          "valueBoolean": true
				        },
				        {
				          "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-unit",
				          "valueCoding": { "system": "http://unitsofmeasure.org", "code": "kg" }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverity(issues, "error"), describe(issues));
		assertFalse(hasSeverity(issues, "fatal"), describe(issues));
		assertFalse(hasSeverity(issues, "warning"), describe(issues));
	}

	@Test
	void plainQuestionnaire_isSilent() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [ { "linkId": "q1", "type": "string", "text": "Q" } ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(issues.isEmpty(), describe(issues));
	}

	@Test
	void codeWithoutSystem_isNotUsable() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Q",
				      "code": [ { "code": "1234-5" } ],
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				          "valueBoolean": true
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "observationExtract requires item.code"), describe(issues));
	}

	@Test
	void displayItem_skipsCodeAndUnitRules() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "d1",
				      "type": "display",
				      "text": "Note",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract",
				          "valueBoolean": true
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverityMessage(issues, "error", "requires item.code"), describe(issues));
		assertFalse(hasSeverityMessage(issues, "error", "requires a unit"), describe(issues));
	}

	private static List<ValidationIssueDto> validate(String theJson) {
		Questionnaire questionnaire = (Questionnaire) ourCtx.newJsonParser().parseResource(theJson);
		return ObservationExtractValidator.validate(questionnaire, theJson);
	}

	private static boolean hasSeverity(List<ValidationIssueDto> theIssues, String theSeverity) {
		String wanted = theSeverity.toLowerCase(Locale.ROOT);
		return theIssues.stream()
				.anyMatch(i -> i.getSeverity() != null && wanted.equals(i.getSeverity().toLowerCase(Locale.ROOT)));
	}

	private static boolean hasSeverityMessage(
			List<ValidationIssueDto> theIssues, String theSeverity, String theSubstring) {
		String wanted = theSeverity.toLowerCase(Locale.ROOT);
		String needle = theSubstring.toLowerCase(Locale.ROOT);
		return theIssues.stream().anyMatch(i -> i.getSeverity() != null
				&& wanted.equals(i.getSeverity().toLowerCase(Locale.ROOT))
				&& i.getMessage() != null
				&& i.getMessage().toLowerCase(Locale.ROOT).contains(needle));
	}

	private static String describe(List<ValidationIssueDto> theIssues) {
		StringBuilder out = new StringBuilder();
		for (ValidationIssueDto issue : theIssues) {
			out.append(issue.getSeverity())
					.append(" @ ")
					.append(issue.getLine())
					.append(':')
					.append(issue.getColumn())
					.append(' ')
					.append(issue.getLocation())
					.append(" — ")
					.append(issue.getMessage())
					.append('\n');
		}
		return out.toString();
	}
}
