package ca.uhn.fhir.questionnairevalidator.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class QuestionnaireValidationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void validateMinimalQuestionnaire_returnsOk() throws Exception {
		String body = """
				{"resourceType":"Questionnaire","status":"draft","item":[{"linkId":"1","type":"string","text":"Q"}]}
				""";
		mockMvc.perform(post("/api/validate-questionnaire")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").exists())
				.andExpect(jsonPath("$.issues").exists());
	}

	@Test
	void validateInvalidJson_returnsFatalIssue() throws Exception {
		mockMvc.perform(post("/api/validate-questionnaire")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ not json"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.issues[0].severity").value("fatal"));
	}

	@Test
	void validateMalformedJson_reportsAllSyntaxErrorsWithRealPositions() throws Exception {
		// Line 3: missing comma after "Questionnaire". Line 4: invalid literal "tru".
		// Both must come back, fatal, at their own positions — not a single FATAL @
		// 1:1.
		String body = "{\n"
				+ "  \"resourceType\": \"Questionnaire\"\n"
				+ "  \"status\": \"draft\",\n"
				+ "  \"active\": tru,\n"
				+ "  \"item\": []\n"
				+ "}";
		mockMvc.perform(post("/api/validate-questionnaire")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.issues.length()").value(2))
				.andExpect(jsonPath("$.issues[0].severity").value("fatal"))
				.andExpect(jsonPath("$.issues[0].location").value("JSON"))
				.andExpect(jsonPath("$.issues[0].line").value(3))
				.andExpect(jsonPath("$.issues[0].column").value(3))
				.andExpect(jsonPath("$.issues[1].severity").value("fatal"))
				.andExpect(jsonPath("$.issues[1].line").value(4))
				.andExpect(jsonPath("$.issues[1].column").value(13));
	}

	@Test
	void validateWrongResourceType_returnsError() throws Exception {
		mockMvc.perform(post("/api/validate-questionnaire")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"resourceType\":\"Patient\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.issues[0].message").value(org.hamcrest.Matchers.containsString("Questionnaire")));
	}

	@Test
	void validateTypeMismatch_reportsAllParseErrorsWithRealLocations() throws Exception {
		// status (line 3) is an integer, item.type (line 7) is a boolean: both are FHIR
		// parse
		// errors, and both must be reported at their own line/column — not just the
		// first at 1:1.
		String body = "{\n"
				+ "  \"resourceType\": \"Questionnaire\",\n"
				+ "  \"status\": 12,\n"
				+ "  \"item\": [\n"
				+ "    {\n"
				+ "      \"linkId\": \"1\",\n"
				+ "      \"type\": true,\n"
				+ "      \"text\": \"Q\"\n"
				+ "    }\n"
				+ "  ]\n"
				+ "}";
		mockMvc.perform(post("/api/validate-questionnaire")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.issues.length()").value(2))
				.andExpect(jsonPath("$.issues[0].severity").value("error"))
				.andExpect(jsonPath("$.issues[0].location").value("Questionnaire.status"))
				.andExpect(jsonPath("$.issues[0].line").value(3))
				.andExpect(jsonPath("$.issues[0].column").value(3))
				.andExpect(jsonPath("$.issues[1].location").value("Questionnaire.item[0].type"))
				.andExpect(jsonPath("$.issues[1].line").value(7))
				.andExpect(jsonPath("$.issues[1].column").value(7));
	}

	@Test
	void validateUnknownElement_reportsParseErrorAtThatElement() throws Exception {
		// "linkI" is not a Questionnaire.item element: previously this was dropped by
		// the lenient parser (and only a missing-linkId validation error at 1:<offset>
		// appeared).
		String body = "{\n"
				+ "  \"resourceType\": \"Questionnaire\",\n"
				+ "  \"status\": \"draft\",\n"
				+ "  \"item\": [\n"
				+ "    {\n"
				+ "      \"linkI\": \"1\",\n"
				+ "      \"type\": \"string\"\n"
				+ "    }\n"
				+ "  ]\n"
				+ "}";
		mockMvc.perform(post("/api/validate-questionnaire")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.issues[0].location").value("Questionnaire.item[0].linkI"))
				.andExpect(jsonPath("$.issues[0].line").value(6))
				.andExpect(jsonPath("$.issues[0].column").value(7))
				.andExpect(jsonPath("$.issues[0].message")
						.value(org.hamcrest.Matchers.containsString("Unknown element 'linkI'")));
	}

	@Test
	void listTerminologyServers_returnsPresets() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/terminology-servers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value("none"))
				.andExpect(jsonPath("$[1].id").value("tx-fhir-org-r4"));
	}

	@Test
	void validateDuplicateLinkIds_enrichesQue2Locations() throws Exception {
		String body = """
				{
				  "resourceType": "Questionnaire",
				  "status": "draft",
				  "item": [
				    { "linkId": "age", "type": "integer", "text": "Age" },
				    { "linkId": "age", "type": "string", "text": "Dup" }
				  ]
				}
				""";
		mockMvc.perform(post("/api/validate-questionnaire")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.issues[?(@.location == 'Questionnaire.item[0].linkId')]").isNotEmpty())
				.andExpect(jsonPath("$.issues[?(@.location == 'Questionnaire.item[1].linkId')]").isNotEmpty())
				.andExpect(jsonPath("$.issues[?(@.message =~ /.*que-2.*/ && @.location == 'Questionnaire')]")
						.isEmpty());
	}

	@Test
	void validateWithDisallowedTerminologyHost_returnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/validate-questionnaire")
				.param("terminologyUrl", "https://evil.example.com/r4")
				.contentType(MediaType.APPLICATION_JSON)
				.content(
						"{\"resourceType\":\"Questionnaire\",\"status\":\"draft\",\"item\":[{\"linkId\":\"1\",\"type\":\"string\"}]}"))
				.andExpect(status().isBadRequest());
	}
}
