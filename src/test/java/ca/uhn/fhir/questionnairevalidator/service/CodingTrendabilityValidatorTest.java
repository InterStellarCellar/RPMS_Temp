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

class CodingTrendabilityValidatorTest {

	private static FhirContext ourCtx;

	@BeforeAll
	static void init() {
		ourCtx = FhirContext.forR4();
	}

	@Test
	void valueItemMissingCode_withMonitoringIntent_isErrorWithLocation() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "url": "http://rpms.local/Questionnaire/ct-missing-code",
				  "version": "0.1.0",
				  "status": "draft",
				  "item": [
				    { "linkId": "q1", "type": "string", "text": "Name" }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "value-producing item requires item.code"), describe(issues));
		assertTrue(issues.stream().anyMatch(i -> i.getLine() != null && i.getLine() > 1), describe(issues));
	}

	@Test
	void valueItemMissingCode_withoutMonitoringIntent_isSilent() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "q1", "type": "string", "text": "Name" }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverityMessage(issues, "error", "value-producing item requires item.code"), describe(issues));
	}

	@Test
	void groupAndDisplay_doNotRequireCode() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "url": "http://rms.local/Questionnaire/ct-group",
				  "version": "0.1.0",
				  "status": "draft",
				  "item": [
				    { "linkId": "g", "type": "group", "text": "G",
				      "item": [
				        { "linkId": "d", "type": "display", "text": "Note" },
				        {
				          "linkId": "q1", "type": "string", "text": "Q",
				          "code": [ { "system": "http://loinc.org", "code": "1234-5" } ]
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverityMessage(issues, "error", "value-producing item requires item.code"), describe(issues));
	}

	@Test
	void answerOptionCodingWithoutSystem_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "choice",
				      "text": "Q",
				      "code": [ { "system": "http://loinc.org", "code": "1234-5" } ],
				      "answerOption": [ { "valueCoding": { "code": "orphan-code" } } ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "Coding must include both system and code"), describe(issues));
	}

	@Test
	void itemCodeExampleSystem_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Q",
				      "code": [ { "system": "http://example.org/made-up", "code": "nope" } ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "example CodeSystem is not a stable coded identity"),
				describe(issues));
	}

	@Test
	void customCodeSystem_isAllowed() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "string",
				      "text": "Q",
				      "code": [ { "system": "http://my-hospital.org/CodeSystem/local", "code": "x" } ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverityMessage(issues, "warning", "not a preferred terminology"), describe(issues));
		assertFalse(hasSeverity(issues, "error"), describe(issues));
	}

	@Test
	void duplicateItemCodes_areAllowed() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "a",
				      "type": "integer",
				      "text": "A",
				      "code": [ { "system": "http://loinc.org", "code": "8867-4" } ]
				    },
				    {
				      "linkId": "b",
				      "type": "integer",
				      "text": "B",
				      "code": [ { "system": "HTTP://LOINC.ORG", "code": "8867-4" } ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(
				hasSeverityMessage(issues, "error", "duplicate item.code across value-producing items"),
				describe(issues));
		assertFalse(hasSeverity(issues, "error"), describe(issues));
	}

	@Test
	void calculatedExpressionMissingOrdinal_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "choice",
				      "text": "Q1",
				      "code": [ { "system": "http://loinc.org", "code": "44250-9" } ],
				      "answerOption": [
				        { "valueCoding": { "system": "http://loinc.org", "code": "LA6568-5" } }
				      ]
				    },
				    {
				      "linkId": "score",
				      "type": "integer",
				      "text": "Score",
				      "code": [ { "system": "http://loinc.org", "code": "44261-6" } ],
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression",
				          "valueExpression": {
				            "language": "text/fhirpath",
				            "expression": "%resource.item.where(linkId='q1').answer.value.ordinal().sum()"
				          }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(
				hasSeverityMessage(issues, "error", "scored choice answerOption requires ordinalValue or itemWeight"),
				describe(issues));
	}

	@Test
	void calculatedExpressionWithOrdinals_isOk() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "choice",
				      "text": "Q1",
				      "code": [ { "system": "http://loinc.org", "code": "44250-9" } ],
				      "answerOption": [
				        {
				          "extension": [
				            { "url": "http://hl7.org/fhir/StructureDefinition/itemWeight", "valueDecimal": 0 }
				          ],
				          "valueCoding": { "system": "http://loinc.org", "code": "LA6568-5" }
				        }
				      ]
				    },
				    {
				      "linkId": "score",
				      "type": "integer",
				      "text": "Score",
				      "code": [ { "system": "http://loinc.org", "code": "44261-6" } ],
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression",
				          "valueExpression": {
				            "language": "text/fhirpath",
				            "expression": "%resource.item.where(linkId='q1').answer.valueCoding.extension('http://hl7.org/fhir/StructureDefinition/itemWeight').valueDecimal"
				          }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(
				hasSeverityMessage(issues, "error", "scored choice answerOption requires ordinalValue or itemWeight"),
				describe(issues));
	}

	@Test
	void cqlCalculatedExpression_isSkipped() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "q1",
				      "type": "choice",
				      "text": "Q1",
				      "code": [ { "system": "http://loinc.org", "code": "44250-9" } ],
				      "answerOption": [
				        { "valueCoding": { "system": "http://loinc.org", "code": "LA6568-5" } }
				      ]
				    },
				    {
				      "linkId": "score",
				      "type": "integer",
				      "text": "Score",
				      "code": [ { "system": "http://loinc.org", "code": "44261-6" } ],
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression",
				          "valueExpression": {
				            "language": "text/cql",
				            "expression": "ScoreFullPhq9"
				          }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(
				hasSeverityMessage(issues, "error", "scored choice answerOption requires ordinalValue or itemWeight"),
				describe(issues));
	}

	private static List<ValidationIssueDto> validate(String theJson) {
		Questionnaire questionnaire = (Questionnaire) ourCtx.newJsonParser().parseResource(theJson);
		return CodingTrendabilityValidator.validate(questionnaire, theJson);
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
