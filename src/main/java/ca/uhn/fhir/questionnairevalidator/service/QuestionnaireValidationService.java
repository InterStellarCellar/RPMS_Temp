package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class QuestionnaireValidationService {

	private final FhirContext myFhirContext;
	private final ValidationSupportChain myValidationSupportChain;

	public QuestionnaireValidationService(FhirContext theFhirContext, ValidationSupportChain theValidationSupportChain) {
		myFhirContext = theFhirContext;
		myValidationSupportChain = theValidationSupportChain;
	}

	public ValidateResponse validate(String theJson, boolean theXsd, boolean theSchematron) {
		ValidateResponse response = new ValidateResponse();
		List<ValidationIssueDto> issues = new ArrayList<>();

		try {
			validateJsonWellFormed(theJson);
		} catch (JsonParseException e) {
			ValidationIssueDto dto = new ValidationIssueDto();
			dto.setSeverity(ResultSeverityEnum.FATAL.getCode());
			dto.setMessage(e.getOriginalMessage());
			if (e.getLocation() != null) {
				dto.setLine(e.getLocation().getLineNr());
				dto.setColumn(e.getLocation().getColumnNr());
			}
			dto.setLocation("JSON");
			issues.add(dto);
			response.setIssues(issues);
			response.setOk(false);
			return response;
		} catch (IOException e) {
			ValidationIssueDto dto = new ValidationIssueDto();
			dto.setSeverity(ResultSeverityEnum.FATAL.getCode());
			dto.setMessage(e.getMessage());
			dto.setLine(1);
			dto.setColumn(1);
			dto.setLocation("JSON");
			issues.add(dto);
			response.setIssues(issues);
			response.setOk(false);
			return response;
		}

		final IBaseResource resource;
		try {
			resource = myFhirContext.newJsonParser().parseResource(theJson);
		} catch (DataFormatException e) {
			ValidationIssueDto dto = new ValidationIssueDto();
			dto.setSeverity(ResultSeverityEnum.ERROR.getCode());
			dto.setMessage(e.getMessage());
			dto.setLine(1);
			dto.setColumn(1);
			dto.setLocation("FHIR parse");
			issues.add(dto);
			response.setIssues(issues);
			response.setOk(false);
			return response;
		}

		if (!"Questionnaire".equals(resource.fhirType())) {
			ValidationIssueDto dto = new ValidationIssueDto();
			dto.setSeverity(ResultSeverityEnum.ERROR.getCode());
			dto.setMessage("Expected resourceType Questionnaire, found: " + resource.fhirType());
			dto.setLine(1);
			dto.setColumn(1);
			dto.setLocation("resourceType");
			issues.add(dto);
			response.setIssues(issues);
			response.setOk(false);
			return response;
		}

		FhirValidator validator = myFhirContext.newValidator();
		FhirInstanceValidator instanceValidator = new FhirInstanceValidator(myFhirContext);
		instanceValidator.setValidationSupport(myValidationSupportChain);
		validator.registerValidatorModule(instanceValidator);
		if (theXsd) {
			validator.setValidateAgainstStandardSchema(true);
		}
		if (theSchematron) {
			validator.setValidateAgainstStandardSchematron(true);
		}

		ValidationResult result = validator.validateWithResult(resource);
		for (SingleValidationMessage next : result.getMessages()) {
			issues.add(toDto(next));
		}
		response.setIssues(issues);
		response.setOk(result.isSuccessful());
		return response;
	}

	private static void validateJsonWellFormed(String theJson) throws JsonParseException, IOException {
		JsonFactory factory = new JsonFactory();
		try (JsonParser parser = factory.createParser(theJson)) {
			while (parser.nextToken() != null) {
				// consume
			}
		}
	}

	private static ValidationIssueDto toDto(SingleValidationMessage theMsg) {
		ValidationIssueDto dto = new ValidationIssueDto();
		if (theMsg.getSeverity() != null) {
			dto.setSeverity(theMsg.getSeverity().getCode());
		}
		dto.setMessage(theMsg.getMessage());
		dto.setLine(theMsg.getLocationLine());
		dto.setColumn(theMsg.getLocationCol());
		dto.setLocation(theMsg.getLocationString());
		return dto;
	}
}
