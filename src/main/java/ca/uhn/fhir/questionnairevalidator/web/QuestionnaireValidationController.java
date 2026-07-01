package ca.uhn.fhir.questionnairevalidator.web;

import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
import ca.uhn.fhir.questionnairevalidator.service.QuestionnaireValidationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QuestionnaireValidationController {

	private final QuestionnaireValidationService myValidationService;

	public QuestionnaireValidationController(QuestionnaireValidationService theValidationService) {
		myValidationService = theValidationService;
	}

	@PostMapping(
			value = "/validate-questionnaire",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ValidateResponse validate(
			@RequestBody String theBody,
			@RequestParam(value = "xsd", defaultValue = "false") boolean theXsd,
			@RequestParam(value = "schematron", defaultValue = "false") boolean theSchematron) {
		return myValidationService.validate(theBody, theXsd, theSchematron);
	}
}
