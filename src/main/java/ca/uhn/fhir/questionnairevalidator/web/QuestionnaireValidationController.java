package ca.uhn.fhir.questionnairevalidator.web;

import ca.uhn.fhir.questionnairevalidator.dto.TerminologyServerDto;
import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
import ca.uhn.fhir.questionnairevalidator.service.QuestionnaireValidationService;
import ca.uhn.fhir.questionnairevalidator.service.TerminologyServerService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class QuestionnaireValidationController {

	private final QuestionnaireValidationService myValidationService;
	private final TerminologyServerService myTerminologyServerService;

	public QuestionnaireValidationController(
			QuestionnaireValidationService theValidationService,
			TerminologyServerService theTerminologyServerService) {
		myValidationService = theValidationService;
		myTerminologyServerService = theTerminologyServerService;
	}

	@GetMapping(value = "/terminology-servers", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<TerminologyServerDto> terminologyServers() {
		return myTerminologyServerService.listPresets();
	}

	@PostMapping(
			value = "/validate-questionnaire",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ValidateResponse validate(
			@RequestBody String theBody,
			@RequestParam(value = "terminologyServer", required = false) String theTerminologyServer,
			@RequestParam(value = "terminologyUrl", required = false) String theTerminologyUrl) {
		String remoteUrl = myTerminologyServerService.resolveRemoteUrl(theTerminologyServer, theTerminologyUrl);
		return myValidationService.validate(theBody, remoteUrl);
	}
}
