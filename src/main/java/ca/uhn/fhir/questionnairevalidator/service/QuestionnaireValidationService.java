package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.questionnairevalidator.config.ValidationBeansConfig;
import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Questionnaire;
import org.springframework.stereotype.Service;

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
	private static final Pattern PARSE_ELEMENT_PATTERN = Pattern.compile("\\[element=\"([^\"]+)\"\\]");
	private static final Pattern PARSE_UNKNOWN_ELEMENT_PATTERN =
			Pattern.compile("Unknown (?:element|attribute) '([^']+)'");
	private static final Pattern PARSE_INVALID_VALUE_PATTERN =
			Pattern.compile("Invalid attribute value \"([^\"]*)\"");
	/** HAPI-1841: "Expected ARRAY at element 'extension', found 'OBJECT'" */
	private static final Pattern PARSE_EXPECTED_ARRAY_ELEMENT_PATTERN =
			Pattern.compile("at element '([^']+)'");
	/** HAPI-1842: "Unexpected array of length N … for element: _title" */
	private static final Pattern PARSE_FOR_ELEMENT_PATTERN =
			Pattern.compile("for element:\\s*(\\S+)");
	/** HAPI-1811: "Extension (URL='…') must not have both a value…" */
	private static final Pattern PARSE_EXTENSION_URL_PATTERN =
			Pattern.compile("Extension \\(URL='([^']+)'\\)");

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

		// Syntax first, with an error-recovering checker: standard parsers (Jackson here, and
		// JSON.parse in the browser) abort at the first syntax error, so they can only ever
		// report one. This reports every independent syntax problem with its real position.
		List<ValidationIssueDto> jsonErrors = JsonSyntaxChecker.check(theJson);
		if (!jsonErrors.isEmpty()) {
			response.setIssues(jsonErrors);
			response.setOk(false);
			return response;
		}

		// Parse with a handler that collects ALL parse problems instead of aborting on the first
		// (StrictErrorHandler) or silently dropping values (LenientErrorHandler). If anything was
		// collected — or parsing still threw — report those errors, located in the raw JSON, and
		// stop: running instance validation on a partially-parsed resource would only add
		// misleading knock-on errors (e.g. "missing" elements whose values failed to parse).
		CollectingParserErrorHandler parseErrorHandler = new CollectingParserErrorHandler();
		IBaseResource resource = null;
		DataFormatException parseException = null;
		try {
			IParser parser = myFhirContext.newJsonParser();
			parser.setParserErrorHandler(parseErrorHandler);
			resource = parser.parseResource(theJson);
		} catch (DataFormatException e) {
			parseException = e;
		}

		if (!parseErrorHandler.getErrors().isEmpty() || parseException != null) {
			List<CollectingParserErrorHandler.ParseError> parseErrors =
					new ArrayList<>(parseErrorHandler.getErrors());
			if (parseException != null) {
				String parseExceptionMessage = parseException.getMessage();
				if (parseErrors.stream().noneMatch(e -> parseExceptionMessage != null
						&& parseExceptionMessage.equals(e.message()))) {
					parseErrors.add(parseErrorFromException(parseExceptionMessage));
				}
			}
			response.setIssues(new JsonElementLocator(theJson).toIssues(parseErrors));
			response.setOk(false);
			return response;
		}

		if (resource == null) {
			ValidationIssueDto dto = new ValidationIssueDto();
			dto.setSeverity(ResultSeverityEnum.ERROR.getCode());
			dto.setMessage("Could not parse resource");
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
		issues = InvariantLocationEnricher.enrich(resource, theJson, issues);
		if (resource instanceof Questionnaire questionnaire) {
			issues.addAll(ExpressionFhirPathValidator.validate(questionnaire, theJson, myFhirContext));
		}
		issues = dedupeIssues(issues);
		response.setIssues(issues);
		response.setOk(result.isSuccessful() && !hasErrorOrFatal(issues));
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

	/** Build a locatable parse error out of a {@link DataFormatException} message. */
	static CollectingParserErrorHandler.ParseError parseErrorFromException(String theMessage) {
		String element = null;
		String offendingValue = null;
		if (theMessage != null) {
			Matcher elementMatcher = PARSE_ELEMENT_PATTERN.matcher(theMessage);
			if (elementMatcher.find()) {
				element = elementMatcher.group(1);
			} else {
				Matcher unknownMatcher = PARSE_UNKNOWN_ELEMENT_PATTERN.matcher(theMessage);
				if (unknownMatcher.find()) {
					element = unknownMatcher.group(1);
				}
			}
			// HAPI-1841: "Expected ARRAY at element 'extension', found 'OBJECT'"
			if (element == null) {
				Matcher arrayElMatcher = PARSE_EXPECTED_ARRAY_ELEMENT_PATTERN.matcher(theMessage);
				if (arrayElMatcher.find()) {
					element = arrayElMatcher.group(1);
				}
			}
			// HAPI-1842: "Unexpected array of length N … for element: _title"
			if (element == null) {
				Matcher forElMatcher = PARSE_FOR_ELEMENT_PATTERN.matcher(theMessage);
				if (forElMatcher.find()) {
					element = forElMatcher.group(1);
				}
			}
			// HAPI-1811: "Extension (URL='…') must not have both a value…"
			// The element is the extension or modifierExtension containing that URL.
			if (element == null) {
				Matcher urlMatcher = PARSE_EXTENSION_URL_PATTERN.matcher(theMessage);
				if (urlMatcher.find()) {
					offendingValue = urlMatcher.group(1);
					element = "url";
				}
			}
			Matcher valueMatcher = PARSE_INVALID_VALUE_PATTERN.matcher(theMessage);
			if (valueMatcher.find()) {
				offendingValue = valueMatcher.group(1);
			}
		}
		return new CollectingParserErrorHandler.ParseError(theMessage, element, null, offendingValue, null);
	}

	private static boolean hasErrorOrFatal(List<ValidationIssueDto> theIssues) {
		for (ValidationIssueDto issue : theIssues) {
			String severity = issue.getSeverity();
			if (severity == null) {
				continue;
			}
			String lowered = severity.toLowerCase(Locale.ROOT);
			if ("error".equals(lowered) || "fatal".equals(lowered)) {
				return true;
			}
		}
		return false;
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
