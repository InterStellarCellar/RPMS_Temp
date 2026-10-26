package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSyntaxCheckerTest {

	@Test
	void validJson_reportsNoErrors() {
		String json = """
				{"resourceType":"Questionnaire","status":"draft","n":-1.5e+10,"ok":true,"x":null,
				 "s":"escapes \\" \\\\ \\u0041 \\n","item":[{"linkId":"1","type":"string","initial":[]}]}
				""";
		assertTrue(JsonSyntaxChecker.check(json).isEmpty());
	}

	@Test
	void collectsMultipleIndependentErrorsWithPositions() {
		// Line 3: missing comma. Line 4: bad literal. Line 6: trailing comma. Line 7:
		// missing comma.
		String json = "{\n"
				+ "  \"resourceType\": \"Questionnaire\"\n"
				+ "  \"status\": \"draft\",\n"
				+ "  \"active\": tru,\n"
				+ "  \"item\": [\n"
				+ "    {\"linkId\": \"1\", \"type\": \"string\", },\n"
				+ "    {\"linkId\": \"2\" \"type\": \"string\"}\n"
				+ "  ]\n"
				+ "}";
		List<ValidationIssueDto> issues = JsonSyntaxChecker.check(json);

		assertEquals(4, issues.size(), () -> "issues: " + describe(issues));
		assertEquals(3, issues.get(0).getLine());
		assertEquals(3, issues.get(0).getColumn());
		assertTrue(issues.get(0).getMessage().contains("Expected ',' or '}'"), describe(issues));
		assertEquals(4, issues.get(1).getLine());
		assertEquals(13, issues.get(1).getColumn());
		assertTrue(issues.get(1).getMessage().contains("Invalid literal 'tru'"), describe(issues));
		assertEquals(6, issues.get(2).getLine());
		assertTrue(issues.get(2).getMessage().contains("Trailing comma"), describe(issues));
		assertEquals(7, issues.get(3).getLine());
		assertTrue(issues.get(3).getMessage().contains("Expected ',' or '}'"), describe(issues));
		for (ValidationIssueDto issue : issues) {
			assertEquals("fatal", issue.getSeverity());
			assertEquals("JSON", issue.getLocation());
		}
	}

	@Test
	void unterminatedObjectAndBadNumberAreBothReported() {
		String json = "{\"a\": 01, \"b\": \"x\"";
		List<ValidationIssueDto> issues = JsonSyntaxChecker.check(json);
		assertEquals(2, issues.size(), () -> "issues: " + describe(issues));
		assertTrue(issues.get(0).getMessage().contains("leading zeros"), describe(issues));
		assertTrue(issues.get(1).getMessage().contains("Unterminated object"), describe(issues));
	}

	@Test
	void invalidEscapesAndTrailingContentAreReported() {
		List<ValidationIssueDto> issues = JsonSyntaxChecker.check("{\"a\": \"bad \\q \\u12\"} extra");
		assertTrue(
				issues.stream().anyMatch(i -> i.getMessage().contains("Invalid escape")),
				describe(issues));
		assertTrue(
				issues.stream().anyMatch(i -> i.getMessage().contains("Unexpected content")),
				describe(issues));
	}

	@Test
	void missingArrayCloseBracket_beforeObjectClose_doesNotHang() {
		// Regression: previously spun forever because '}' inside an open array did not advance
		// myPos and duplicate errors were collapsed (so MAX_ERRORS never tripped).
		String json = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {"linkId": "1", "type": "string"}
				}
				""";
		List<ValidationIssueDto> issues = JsonSyntaxChecker.check(json);
		assertTrue(
				issues.stream().anyMatch(i -> i.getMessage().contains("Expected ',' or ']'")),
				() -> "issues: " + describe(issues));
		assertTrue(issues.size() >= 1 && issues.size() < 50, () -> "issues: " + describe(issues));
	}

	@Test
	void wrongCloserInObject_doesNotHang() {
		String json = "{\"a\": 1]";
		List<ValidationIssueDto> issues = JsonSyntaxChecker.check(json);
		assertTrue(
				issues.stream().anyMatch(i -> i.getMessage().contains("Expected ',' or '}'")),
				() -> "issues: " + describe(issues));
	}

	private static String describe(List<ValidationIssueDto> theIssues) {
		StringBuilder out = new StringBuilder();
		for (ValidationIssueDto issue : theIssues) {
			out.append('[')
					.append(issue.getLine())
					.append(':')
					.append(issue.getColumn())
					.append(' ')
					.append(issue.getMessage())
					.append("] ");
		}
		return out.toString();
	}
}
