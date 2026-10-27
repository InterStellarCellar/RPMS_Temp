package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.hl7.fhir.r4.model.Questionnaire;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionFhirPathValidatorTest {

	private static FhirContext ourCtx;

	@BeforeAll
	static void init() {
		ourCtx = FhirContext.forR4();
	}

	@Test
	void brokenSyntax_isError() {
		String json = questionnaireWithItemExpression(
				"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression",
				"text/fhirpath",
				"this is not valid FHIRPath ((((%patient.!!!)))");
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "Invalid FHIRPath"), describe(issues));
		assertTrue(issues.stream().anyMatch(i -> i.getLocation() != null && i.getLocation().contains("expression")),
				describe(issues));
		assertTrue(issues.stream().anyMatch(i -> i.getLine() != null && i.getLine() > 1), describe(issues));
	}

	@Test
	void validCirgStyleIif_isClean() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "AUDIT-0", "type": "choice", "text": "Q" },
				    { "linkId": "AUDIT-C-score", "type": "decimal", "text": "S" },
				    {
				      "linkId": "flag",
				      "type": "string",
				      "text": "F",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression",
				          "valueExpression": {
				            "language": "text/fhirpath",
				            "expression": "iif(%patient.gender = 'male' and %resource.item.where(linkId='AUDIT-C-score').answer.valueDecimal.toInteger() >= 4, 'At-risk', 'Not at-risk')"
				          }
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
	void textCql_isSkippedWithoutError() {
		String json = questionnaireWithItemExpression(
				"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-enableWhenExpression",
				"text/cql",
				"define X: 1 + 1");
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverity(issues, "error"), describe(issues));
		assertTrue(hasSeverityMessage(issues, "information", "text/cql"), describe(issues));
	}

	@Test
	void fhirQuery_isSkippedWithoutError() {
		String json = questionnaireWithItemExpression(
				"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression",
				"application/x-fhir-query",
				"Patient?name=smith");
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverity(issues, "error"), describe(issues));
		assertTrue(hasSeverityMessage(issues, "information", "application/x-fhir-query"), describe(issues));
	}

	@Test
	void questionnaireConstraintValueString_broken_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "a",
				      "type": "string",
				      "text": "A",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-constraint",
				          "extension": [
				            { "url": "key", "valueId": "k1" },
				            { "url": "severity", "valueCode": "error" },
				            { "url": "expression", "valueString": "1 = ((((" },
				            { "url": "human", "valueString": "must be nonsense" }
				          ]
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "Invalid FHIRPath"), describe(issues));
	}

	@Test
	void variableSelfReference_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "extension": [
				    {
				      "url": "http://hl7.org/fhir/StructureDefinition/variable",
				      "valueExpression": {
				        "name": "x",
				        "language": "text/fhirpath",
				        "expression": "%x + 1"
				      }
				    }
				  ],
				  "item": [ { "linkId": "q1", "type": "string", "text": "Q" } ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "refers to itself"), describe(issues));
	}

	@Test
	void missingLinkIdLiteral_onCalculatedExpression_isError() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "a", "type": "integer", "text": "A" },
				    {
				      "linkId": "b",
				      "type": "integer",
				      "text": "B",
				      "extension": [
				        {
				          "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression",
				          "valueExpression": {
				            "language": "text/fhirpath",
				            "expression": "%resource.item.where(linkId='does-not-exist').answer.value"
				          }
				        }
				      ]
				    }
				  ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertTrue(hasSeverityMessage(issues, "error", "unknown linkId"), describe(issues));
		assertFalse(hasSeverityMessage(issues, "warning", "unknown linkId"), describe(issues));
	}

	@Test
	void missingLinkIdLiteral_onVariable_isWarning() {
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "extension": [
				    {
				      "url": "http://hl7.org/fhir/StructureDefinition/variable",
				      "valueExpression": {
				        "name": "x",
				        "language": "text/fhirpath",
				        "expression": "%resource.item.where(linkId='does-not-exist').answer.value"
				      }
				    }
				  ],
				  "item": [ { "linkId": "a", "type": "integer", "text": "A" } ]
				}
				""";
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverityMessage(issues, "error", "unknown linkId"), describe(issues));
		assertTrue(hasSeverityMessage(issues, "warning", "unknown linkId"), describe(issues));
	}

	@Test
	void undeclaredEnvVar_isWarning() {
		String json = questionnaireWithItemExpression(
				"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression",
				"text/fhirpath",
				"%foo.name");
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverity(issues, "error"), describe(issues));
		assertTrue(hasSeverityMessage(issues, "warning", "%foo"), describe(issues));
	}

	@Test
	void patientWithoutLaunchContext_isAllowed() {
		String json = questionnaireWithItemExpression(
				"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression",
				"text/fhirpath",
				"%patient.name.first().given.first()");
		List<ValidationIssueDto> issues = validate(json);
		assertFalse(hasSeverity(issues, "error"), describe(issues));
		assertFalse(hasSeverity(issues, "warning"), describe(issues));
	}

	@Test
	void shortName_stripsSdcPrefix() {
		assertEquals(
				"initialExpression",
				ExpressionFhirPathValidator.shortName(
						"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression"));
		assertEquals("variable", ExpressionFhirPathValidator.shortName(ExpressionFhirPathValidator.URL_VARIABLE));
	}

	@Test
	void isSkippedLanguage_matchesCqlAndQuery() {
		assertTrue(ExpressionFhirPathValidator.isSkippedLanguage("text/cql"));
		assertTrue(ExpressionFhirPathValidator.isSkippedLanguage("application/x-fhir-query"));
		assertFalse(ExpressionFhirPathValidator.isSkippedLanguage("text/fhirpath"));
	}

	private static List<ValidationIssueDto> validate(String theJson) {
		Questionnaire questionnaire = (Questionnaire) ourCtx.newJsonParser().parseResource(theJson);
		return ExpressionFhirPathValidator.validate(questionnaire, theJson, ourCtx);
	}

	private static String questionnaireWithItemExpression(String theUrl, String theLanguage, String theExpr) {
		return "{\n"
				+ "  \"resourceType\": \"Questionnaire\",\n"
				+ "  \"status\": \"draft\",\n"
				+ "  \"item\": [\n"
				+ "    {\n"
				+ "      \"linkId\": \"q1\",\n"
				+ "      \"type\": \"string\",\n"
				+ "      \"text\": \"Q\",\n"
				+ "      \"extension\": [\n"
				+ "        {\n"
				+ "          \"url\": \"" + theUrl + "\",\n"
				+ "          \"valueExpression\": {\n"
				+ "            \"language\": \"" + theLanguage + "\",\n"
				+ "            \"expression\": \"" + escapeJson(theExpr) + "\"\n"
				+ "          }\n"
				+ "        }\n"
				+ "      ]\n"
				+ "    }\n"
				+ "  ]\n"
				+ "}\n";
	}

	private static String escapeJson(String theValue) {
		return theValue.replace("\\", "\\\\").replace("\"", "\\\"");
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
