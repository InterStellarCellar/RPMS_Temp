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

class MonitoringExpressionUxValidatorTest {

	private static FhirContext ourCtx;

	@BeforeAll
	static void init() {
		ourCtx = FhirContext.forR4();
	}

	@Test
	void scoringCalculatedExpressionOnString_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "q1", "type": "choice", "text": "Q" },
				    {
				      "linkId": "score",
				      "type": "string",
				      "text": "Score",
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
		assertTrue(
				hasSeverityMessage(issues, "error", "calculatedExpression scoring requires item.type integer or decimal"),
				describe(issues));
	}

	@Test
	void scoringCalculatedExpressionOnInteger_isClean() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "q1", "type": "choice", "text": "Q" },
				    {
				      "linkId": "score",
				      "type": "integer",
				      "text": "Score",
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
				hasSeverityMessage(issues, "error", "calculatedExpression scoring requires item.type"),
				describe(issues));
	}

	@Test
	void nonScoringCalculatedExpressionOnString_isSkipped() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "flag",
				      "type": "string",
				      "text": "Flag",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression",
				          "valueExpression": {
				            "language": "text/fhirpath",
				            "expression": "iif(true, 'At-risk', 'Not at-risk')"
				          }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(
				hasSeverityMessage(issues, "error", "calculatedExpression scoring requires item.type"),
				describe(issues));
	}

	@Test
	void circularEnableWhen_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "a",
				      "type": "boolean",
				      "text": "A",
				      "enableWhen": [ { "question": "b", "operator": "=", "answerBoolean": true } ]
				    },
				    {
				      "linkId": "b",
				      "type": "boolean",
				      "text": "B",
				      "enableWhen": [ { "question": "a", "operator": "=", "answerBoolean": true } ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "circular enableWhen dependency"), describe(issues));
	}

	@Test
	void selfEnableWhen_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "a",
				      "type": "boolean",
				      "text": "A",
				      "enableWhen": [ { "question": "a", "operator": "=", "answerBoolean": true } ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "circular enableWhen dependency"), describe(issues));
	}

	@Test
	void readOnlyWithoutValueSource_isWarning() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "q1", "type": "string", "text": "Q", "readOnly": true }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(
				hasSeverityMessage(
						issues,
						"warning",
						"readOnly item has no initial, calculatedExpression, or initialExpression"),
				describe(issues));
		assertFalse(hasSeverity(issues, "error"), describe(issues));
	}

	@Test
	void readOnlyWithCalculatedExpression_isClean() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "a", "type": "integer", "text": "A" },
				    {
				      "linkId": "score",
				      "type": "integer",
				      "text": "Score",
				      "readOnly": true,
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression",
				          "valueExpression": {
				            "language": "text/fhirpath",
				            "expression": "%resource.item.where(linkId='a').answer.valueInteger"
				          }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(
				hasSeverityMessage(issues, "warning", "readOnly item has no initial"),
				describe(issues));
	}

	@Test
	void readOnlyWithInitial_isClean() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "locked",
				      "type": "string",
				      "text": "Locked",
				      "readOnly": true,
				      "initial": [ { "valueString": "system-set" } ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(
				hasSeverityMessage(issues, "warning", "readOnly item has no initial"),
				describe(issues));
	}

	private static List<ValidationIssueDto> validate(String theJson) {
		Questionnaire questionnaire = (Questionnaire) ourCtx.newJsonParser().parseResource(theJson);
		return MonitoringExpressionUxValidator.validate(questionnaire, theJson);
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
