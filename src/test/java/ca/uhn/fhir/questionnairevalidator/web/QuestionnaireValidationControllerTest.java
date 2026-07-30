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
		String body =
				"""
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
	void validateWrongResourceType_returnsError() throws Exception {
		mockMvc.perform(post("/api/validate-questionnaire")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"resourceType\":\"Patient\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.issues[0].message").value(org.hamcrest.Matchers.containsString("Questionnaire")));
	}

	@Test
	void listTerminologyServers_returnsPresets() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/terminology-servers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value("none"))
				.andExpect(jsonPath("$[1].id").value("tx-fhir-org-r4"));
	}

	@Test
	void validateWithDisallowedTerminologyHost_returnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/validate-questionnaire")
						.param("terminologyUrl", "https://evil.example.com/r4")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"resourceType\":\"Questionnaire\",\"status\":\"draft\",\"item\":[{\"linkId\":\"1\",\"type\":\"string\"}]}"))
				.andExpect(status().isBadRequest());
	}
}
