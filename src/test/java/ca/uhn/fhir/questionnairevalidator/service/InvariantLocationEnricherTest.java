package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Questionnaire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvariantLocationEnricherTest {

	private static final String QUE2_MESSAGE =
			"Constraint failed: que-2: 'The link ids for groups and questions must be unique within the questionnaire'";

	@Test
	void constraintKey_extractsQue2() {
		assertEquals("que-2", InvariantLocationEnricher.constraintKey(QUE2_MESSAGE));
		assertEquals(null, InvariantLocationEnricher.constraintKey("Unknown code 'x'"));
	}

	@Test
	void enrich_expandsQue2ToEachDuplicateLinkId() {
		String json =
				"""
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "age", "type": "integer", "text": "Age" },
				    { "linkId": "age", "type": "string", "text": "Dup" }
				  ]
				}
				""";
		Questionnaire q = questionnaireWithDuplicateAges();
		ValidationIssueDto coarse = issue("error", QUE2_MESSAGE, 1, 100, "Questionnaire");

		List<ValidationIssueDto> enriched = InvariantLocationEnricher.enrich(q, json, List.of(coarse));

		assertEquals(2, enriched.size());
		assertEquals("Questionnaire.item[0].linkId", enriched.get(0).getLocation());
		assertEquals("Questionnaire.item[1].linkId", enriched.get(1).getLocation());
		assertTrue(enriched.get(0).getMessage().contains("linkId \"age\""));
		assertTrue(enriched.get(1).getMessage().contains("linkId \"age\""));
		// Pretty JSON: both linkId keys sit on their own item lines (not HAPI's 1:<offset>).
		assertEquals(5, enriched.get(0).getLine());
		assertEquals(6, enriched.get(1).getLine());
		assertTrue(enriched.get(0).getColumn() > 1);
		assertTrue(enriched.get(1).getColumn() > 1);
	}

	@Test
	void enrich_handlesNestedDuplicates() {
		String json =
				"""
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    {
				      "linkId": "g1",
				      "type": "group",
				      "item": [
				        { "linkId": "x", "type": "string" }
				      ]
				    },
				    { "linkId": "x", "type": "string" }
				  ]
				}
				""";
		Questionnaire q = new Questionnaire();
		q.setStatus(Enumerations.PublicationStatus.DRAFT);
		Questionnaire.QuestionnaireItemComponent group = q.addItem();
		group.setLinkId("g1");
		group.setType(Questionnaire.QuestionnaireItemType.GROUP);
		group.addItem().setLinkId("x").setType(Questionnaire.QuestionnaireItemType.STRING);
		q.addItem().setLinkId("x").setType(Questionnaire.QuestionnaireItemType.STRING);

		ValidationIssueDto coarse = issue("error", QUE2_MESSAGE, 1, 50, "Questionnaire");
		List<ValidationIssueDto> enriched = InvariantLocationEnricher.enrich(q, json, List.of(coarse));

		assertEquals(2, enriched.size());
		assertEquals("Questionnaire.item[0].item[0].linkId", enriched.get(0).getLocation());
		assertEquals("Questionnaire.item[1].linkId", enriched.get(1).getLocation());
	}

	@Test
	void enrich_leavesNonQue2IssuesUntouched() {
		Questionnaire q = questionnaireWithDuplicateAges();
		String json = "{\"resourceType\":\"Questionnaire\",\"status\":\"draft\",\"item\":[]}";
		ValidationIssueDto other = issue("warning", "Constraint failed: que-13: '…'", 3, 5, "Questionnaire.item[0]");

		List<ValidationIssueDto> enriched = InvariantLocationEnricher.enrich(q, json, List.of(other));

		assertEquals(1, enriched.size());
		assertEquals(other.getMessage(), enriched.get(0).getMessage());
		assertEquals(other.getLocation(), enriched.get(0).getLocation());
	}

	@Test
	void enrich_preservesSiblingIssuesWhenExpandingQue2() {
		Questionnaire q = questionnaireWithDuplicateAges();
		String json =
				"""
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "age", "type": "integer" },
				    { "linkId": "age", "type": "string" }
				  ]
				}
				""";
		ValidationIssueDto narrative = issue("warning", "Constraint failed: dom-6: 'A resource should have narrative'", 1, 1, "Questionnaire");
		ValidationIssueDto que2 = issue("error", QUE2_MESSAGE, 1, 200, "Questionnaire");

		List<ValidationIssueDto> enriched =
				InvariantLocationEnricher.enrich(q, json, List.of(narrative, que2));

		assertEquals(3, enriched.size());
		assertEquals(narrative.getMessage(), enriched.get(0).getMessage());
		assertEquals("Questionnaire.item[0].linkId", enriched.get(1).getLocation());
		assertEquals("Questionnaire.item[1].linkId", enriched.get(2).getLocation());
	}

	private static Questionnaire questionnaireWithDuplicateAges() {
		Questionnaire q = new Questionnaire();
		q.setStatus(Enumerations.PublicationStatus.DRAFT);
		q.addItem().setLinkId("age").setType(Questionnaire.QuestionnaireItemType.INTEGER);
		q.addItem().setLinkId("age").setType(Questionnaire.QuestionnaireItemType.STRING);
		return q;
	}

	private static ValidationIssueDto issue(
			String severity, String message, int line, int column, String location) {
		ValidationIssueDto dto = new ValidationIssueDto();
		dto.setSeverity(severity);
		dto.setMessage(message);
		dto.setLine(line);
		dto.setColumn(column);
		dto.setLocation(location);
		return dto;
	}
}
