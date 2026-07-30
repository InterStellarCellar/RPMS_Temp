package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.questionnairevalidator.config.ValidationBeansConfig;
import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QuestionnaireValidationService {

	private static final Pattern UNKNOWN_CODE_PATTERN = Pattern.compile(
			"Unknown code\\s+['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
	private static final Pattern SYSTEM_CODE_PATTERN = Pattern.compile(
			"(https?://[^\\s#'\"<>]+#[^\\s'\".,;:)\\]]+)");

	private final FhirContext myFhirContext;
	private final ValidationSupportChain myLocalValidationSupportChain;

	public QuestionnaireValidationService(
			FhirContext theFhirContext, ValidationSupportChain theLocalValidationSupportChain) {
		myFhirContext = theFhirContext;
		myLocalValidationSupportChain = theLocalValidationSupportChain;
	}

	/**
	 * @param theRemoteTerminologyUrl empty/null for local-only; otherwise FHIR Tx base URL
	 */
	public ValidateResponse validate(String theJson, String theRemoteTerminologyUrl) {
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

		IValidationSupport support = ValidationBeansConfig.withOptionalRemote(
				myFhirContext, myLocalValidationSupportChain, theRemoteTerminologyUrl);

		FhirValidator validator = myFhirContext.newValidator();
		FhirInstanceValidator instanceValidator = new FhirInstanceValidator(myFhirContext);
		instanceValidator.setValidationSupport(support);
		validator.registerValidatorModule(instanceValidator);

		ValidationResult result = validator.validateWithResult(resource);
		for (SingleValidationMessage next : result.getMessages()) {
			issues.add(toDto(next));
		}
		response.setIssues(dedupeIssues(issues));
		response.setOk(result.isSuccessful());
		return response;
	}

	/**
	 * HAPI often emits both a core terminology issue and a remote-Tx wrapper for the same coding.
	 * Policy:
	 * - If a remote terminology wrapper exists for a location, keep that (remote code-system check).
	 * - Otherwise keep the core message (structural / local / common-code-system issues).
	 */
	static List<ValidationIssueDto> dedupeIssues(List<ValidationIssueDto> theIssues) {
		Map<String, ValidationIssueDto> bestByKey = new LinkedHashMap<>();
		for (ValidationIssueDto issue : theIssues) {
			String key = dedupeKey(issue);
			ValidationIssueDto existing = bestByKey.get(key);
			if (existing == null || prefer(issue, existing)) {
				bestByKey.put(key, issue);
			}
		}

		// Drop core twins when a remote terminology wrapper exists at the same location/severity.
		List<ValidationIssueDto> locationHasRemote = new ArrayList<>();
		for (ValidationIssueDto issue : bestByKey.values()) {
			if (isRemoteTerminologyWrapper(issue.getMessage())) {
				locationHasRemote.add(issue);
			}
		}
		List<ValidationIssueDto> out = new ArrayList<>();
		for (ValidationIssueDto issue : bestByKey.values()) {
			if (!isRemoteTerminologyWrapper(issue.getMessage())
					&& locationHasRemote.stream().anyMatch(remote -> sameLocationAndSeverity(remote, issue))) {
				continue;
			}
			out.add(issue);
		}
		return out;
	}

	private static boolean sameLocationAndSeverity(ValidationIssueDto a, ValidationIssueDto b) {
		String sevA = a.getSeverity() == null ? "" : a.getSeverity().toLowerCase(Locale.ROOT);
		String sevB = b.getSeverity() == null ? "" : b.getSeverity().toLowerCase(Locale.ROOT);
		return sevA.equals(sevB)
				&& Objects.equals(a.getLine(), b.getLine())
				&& Objects.equals(a.getColumn(), b.getColumn());
	}

	private static String dedupeKey(ValidationIssueDto theIssue) {
		String severity = theIssue.getSeverity() == null ? "" : theIssue.getSeverity().toLowerCase(Locale.ROOT);
		String concept = extractConceptKey(theIssue.getMessage());
		if (concept == null) {
			// Fall back to location-only for pairing core/remote twins with mismatched wording
			// (e.g. "Wrong Display Name" vs "Unknown code … Remote Terminology server").
			if (isRemoteTerminologyWrapper(theIssue.getMessage()) || looksLikeTerminologyIssue(theIssue.getMessage())) {
				return severity + "|" + theIssue.getLine() + "|" + theIssue.getColumn() + "|*term*";
			}
			concept = theIssue.getMessage() == null ? "" : theIssue.getMessage();
		}
		return severity + "|" + theIssue.getLine() + "|" + theIssue.getColumn() + "|" + concept.toLowerCase(Locale.ROOT);
	}

	private static boolean looksLikeTerminologyIssue(String theMessage) {
		if (theMessage == null) {
			return false;
		}
		String m = theMessage.toLowerCase(Locale.ROOT);
		return m.contains("unknown code")
				|| m.contains("wrong display")
				|| m.contains("codesystem")
				|| m.contains("value set")
				|| m.contains("valueset");
	}

	private static String extractConceptKey(String theMessage) {
		if (theMessage == null) {
			return null;
		}
		Matcher unknown = UNKNOWN_CODE_PATTERN.matcher(theMessage);
		if (unknown.find()) {
			return normalizeConceptToken(unknown.group(1).trim());
		}
		Matcher systemCode = SYSTEM_CODE_PATTERN.matcher(theMessage);
		if (systemCode.find()) {
			return normalizeConceptToken(systemCode.group(1).trim());
		}
		return null;
	}

	private static String normalizeConceptToken(String theRaw) {
		int hash = theRaw.lastIndexOf('#');
		if (hash >= 0 && hash < theRaw.length() - 1) {
			return theRaw.substring(0, hash).toLowerCase(Locale.ROOT) + "#" + theRaw.substring(hash + 1);
		}
		return theRaw;
	}

	private static boolean prefer(ValidationIssueDto theCandidate, ValidationIssueDto theExisting) {
		boolean candidateRemote = isRemoteTerminologyWrapper(theCandidate.getMessage());
		boolean existingRemote = isRemoteTerminologyWrapper(theExisting.getMessage());
		if (candidateRemote != existingRemote) {
			// Prefer the remote terminology wrapper when both exist.
			return candidateRemote;
		}
		int candidateLen = theCandidate.getMessage() == null ? 0 : theCandidate.getMessage().length();
		int existingLen = theExisting.getMessage() == null ? 0 : theExisting.getMessage().length();
		return candidateLen < existingLen;
	}

	private static boolean isRemoteTerminologyWrapper(String theMessage) {
		return theMessage != null && theMessage.contains("The Remote Terminology server");
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
