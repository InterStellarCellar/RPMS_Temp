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

class RangeAndUnitSafetyValidatorTest {

	private static FhirContext ourCtx;

	@BeforeAll
	static void init() {
		ourCtx = FhirContext.forR4();
	}

	@Test
	void minGreaterThanMax_isErrorWithLocation() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "age",
				      "type": "integer",
				      "text": "Age",
				      "extension": [
				        { "url": "http://hl7.org/fhir/StructureDefinition/minValue", "valueInteger": 120 },
				        { "url": "http://hl7.org/fhir/StructureDefinition/maxValue", "valueInteger": 0 }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "minValue is greater than maxValue"), describe(issues));
		assertTrue(issues.stream().anyMatch(i -> i.getLine() != null && i.getLine() > 1), describe(issues));
		assertTrue(
				issues.stream().anyMatch(i -> i.getLocation() != null && i.getLocation().contains("extension")),
				describe(issues));
	}

	@Test
	void minEqualsMax_isWarning() {
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
				        { "url": "http://hl7.org/fhir/StructureDefinition/minValue", "valueInteger": 5 },
				        { "url": "http://hl7.org/fhir/StructureDefinition/maxValue", "valueInteger": 5 }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "warning", "minValue equals maxValue"), describe(issues));
		assertFalse(hasSeverityMessage(issues, "error", "minValue is greater than maxValue"), describe(issues));
	}

	@Test
	void minMaxTypeMismatch_isSkipped() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "age",
				      "type": "integer",
				      "text": "Age",
				      "extension": [
				        { "url": "http://hl7.org/fhir/StructureDefinition/minValue", "valueDate": "2000-01-01" },
				        { "url": "http://hl7.org/fhir/StructureDefinition/maxValue", "valueInteger": 120 }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverityMessage(issues, "error", "minValue is greater than maxValue"), describe(issues));
		assertFalse(hasSeverityMessage(issues, "warning", "minValue equals maxValue"), describe(issues));
	}

	@Test
	void sliderStepLargerThanRange_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "integer",
				      "text": "Slider",
				      "extension": [
				        { "url": "http://hl7.org/fhir/StructureDefinition/minValue", "valueInteger": 0 },
				        { "url": "http://hl7.org/fhir/StructureDefinition/maxValue", "valueInteger": 10 },
				        { "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-sliderStepValue", "valueInteger": 100 }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(
				hasSeverityMessage(issues, "error", "sliderStepValue is larger than maxValue minus minValue"),
				describe(issues));
	}

	@Test
	void sliderStepEqualToRange_isOk() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "integer",
				      "text": "Slider",
				      "extension": [
				        { "url": "http://hl7.org/fhir/StructureDefinition/minValue", "valueInteger": 0 },
				        { "url": "http://hl7.org/fhir/StructureDefinition/maxValue", "valueInteger": 10 },
				        { "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-sliderStepValue", "valueInteger": 10 }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverityMessage(issues, "error", "sliderStepValue"), describe(issues));
	}

	@Test
	void hiddenAndRequired_isWarning() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Hidden",
				      "required": true,
				      "extension": [
				        { "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden", "valueBoolean": true }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "warning", "hidden item should not be required"), describe(issues));
	}

	@Test
	void hiddenNotRequired_isSilent() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Hidden",
				      "extension": [
				        { "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden", "valueBoolean": true }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverityMessage(issues, "warning", "hidden item should not be required"), describe(issues));
	}

	@Test
	void weightWithCelsius_isDimensionWarning() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "weight",
				      "type": "quantity",
				      "text": "Weight",
				      "code": [ { "system": "http://loinc.org", "code": "29463-7" } ],
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-unit",
				          "valueCoding": { "system": "http://unitsofmeasure.org", "code": "Cel" }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(
				hasSeverityMessage(issues, "warning", "unit dimension does not match concept"),
				describe(issues));
	}

	@Test
	void spo2MaxOver100_isPhysWarning() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "spo2",
				      "type": "integer",
				      "text": "SpO2",
				      "code": [ { "system": "http://loinc.org", "code": "59408-5" } ],
				      "extension": [
				        { "url": "http://hl7.org/fhir/StructureDefinition/minValue", "valueInteger": 50 },
				        { "url": "http://hl7.org/fhir/StructureDefinition/maxValue", "valueInteger": 120 }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(
				hasSeverityMessage(issues, "warning", "declared value range is outside typical physiological bounds"),
				describe(issues));
	}

	@Test
	void heartRateSaneRange_isClean() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "hr",
				      "type": "integer",
				      "text": "HR",
				      "code": [ { "system": "http://loinc.org", "code": "8867-4" } ],
				      "extension": [
				        { "url": "http://hl7.org/fhir/StructureDefinition/minValue", "valueInteger": 30 },
				        { "url": "http://hl7.org/fhir/StructureDefinition/maxValue", "valueInteger": 180 },
				        {
				          "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-unit",
				          "valueCoding": { "system": "http://unitsofmeasure.org", "code": "/min" }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverity(issues, "error"), describe(issues));
		assertFalse(hasSeverityMessage(issues, "warning", "unit dimension"), describe(issues));
		assertFalse(hasSeverityMessage(issues, "warning", "physiological bounds"), describe(issues));
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
	void unresolvedUnitValueSet_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "quantity",
				      "text": "Q",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-unitValueSet",
				          "valueCanonical": "http://example.org/ValueSet/units-missing"
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validateWithSupport(json);
		assertTrue(hasSeverityMessage(issues, "error", "unitValueSet could not be resolved"), describe(issues));
	}

	@Test
	void unknownUcum_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "quantity",
				      "text": "Q",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-unit",
				          "valueCoding": { "system": "http://unitsofmeasure.org", "code": "notARealUcumCode" }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validateWithSupport(json);
		assertTrue(hasSeverityMessage(issues, "error", "Unknown UCUM unit 'notARealUcumCode'"), describe(issues));
	}

	private static List<ValidationIssueDto> validate(String theJson) {
		Questionnaire questionnaire = (Questionnaire) ourCtx.newJsonParser().parseResource(theJson);
		return RangeAndUnitSafetyValidator.validate(questionnaire, theJson, null);
	}

	private static List<ValidationIssueDto> validateWithSupport(String theJson) {
		Questionnaire questionnaire = (Questionnaire) ourCtx.newJsonParser().parseResource(theJson);
		return RangeAndUnitSafetyValidator.validate(questionnaire, theJson, CorpusTestSupport.chain());
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
