package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Expression;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.UriType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authoring-time checks for embedded FHIRPath on a Questionnaire: parse
 * {@code text/fhirpath} (and implied-FHIRPath strings), plus static smells
 * (variable self-reference, unknown {@code linkId} literals, undeclared {@code %} vars).
 *
 * <p>Does not compile CQL, execute {@code application/x-fhir-query}, or evaluate
 * expressions against a QuestionnaireResponse.
 */
class ExpressionFhirPathValidator {

	static final String LANG_FHIRPATH = "text/fhirpath";
	static final String LANG_CQL = "text/cql";
	static final String LANG_FHIR_QUERY = "application/x-fhir-query";

	static final String URL_VARIABLE = "http://hl7.org/fhir/StructureDefinition/variable";
	static final String URL_CONSTRAINT = "http://hl7.org/fhir/StructureDefinition/questionnaire-constraint";
	static final String URL_LAUNCH_CONTEXT =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-launchContext";
	static final String URL_ASSEMBLE_CONTEXT =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-assembleContext";
	static final String URL_EXTRACT_ALLOCATE_ID =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-extractAllocateId";
	static final String URL_TEMPLATE_EXTRACT_VALUE =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractValue";
	static final String URL_TEMPLATE_EXTRACT_CONTEXT =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtractContext";
	static final String URL_DEFINITION_EXTRACT =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-definitionExtract";
	static final String URL_TEMPLATE_EXTRACT =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-templateExtract";
	static final String URL_OBSERVATION_EXTRACT_ENTRY =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtractEntry";
	static final String URL_CALCULATED_EXPRESSION =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression";
	static final String URL_ENABLE_WHEN_EXPRESSION =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-enableWhenExpression";
	static final String URL_INITIAL_EXPRESSION =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression";

	private static final Set<String> EXTRACT_PARENT_URLS = Set.of(
			URL_DEFINITION_EXTRACT, URL_TEMPLATE_EXTRACT, URL_OBSERVATION_EXTRACT_ENTRY);

	/** Monitoring UX expression sites: unknown linkId literals are errors (not warnings). */
	private static final Set<String> ESCALATED_LINK_ID_URLS = Set.of(
			URL_CALCULATED_EXPRESSION, URL_ENABLE_WHEN_EXPRESSION, URL_INITIAL_EXPRESSION);
	private static final Set<String> EXTRACT_SLICE_NAMES = Set.of(
			"fullUrl", "ifNoneMatch", "ifModifiedSince", "ifMatch", "ifNoneExist", "resourceId");

	/** Always-legal FHIRPath / SDC environment names (no leading %). */
	private static final Set<String> BUILTIN_ENV = Set.of(
			"resource",
			"rootResource",
			"context",
			"factory",
			"terminologies",
			"questionnaire",
			"qitem",
			"patient",
			"encounter",
			"location",
			"user",
			"study",
			"healthCareService",
			"ucum",
			"sct",
			"loinc",
			"today",
			"now");

	private static final Pattern ENV_VAR = Pattern.compile("%([A-Za-z_][A-Za-z0-9_]*)");
	private static final Pattern LINK_ID_LITERAL =
			Pattern.compile("linkId\\s*=\\s*['\"]([^'\"]+)['\"]");

	private final IFhirPath myFhirPath;
	private final JsonElementLocator myLocator;
	private final Set<String> myLinkIds = new LinkedHashSet<>();
	private final Set<String> myDeclaredEnv = new LinkedHashSet<>(BUILTIN_ENV);
	private final List<ValidationIssueDto> myIssues = new ArrayList<>();

	private ExpressionFhirPathValidator(FhirContext theFhirContext, String theJson) {
		myFhirPath = theFhirContext.newFhirPath();
		myLocator = new JsonElementLocator(theJson);
	}

	static List<ValidationIssueDto> validate(
			Questionnaire theQuestionnaire, String theJson, FhirContext theFhirContext) {
		ExpressionFhirPathValidator validator = new ExpressionFhirPathValidator(theFhirContext, theJson);
		validator.collectContext(theQuestionnaire);
		validator.walkQuestionnaire(theQuestionnaire);
		return validator.myIssues;
	}

	// ---------------------------------------------------------- collect context

	private void collectContext(Questionnaire theQuestionnaire) {
		collectLinkIds(theQuestionnaire.getItem());
		collectDeclaredFromExtensions(theQuestionnaire.getExtension());
		collectDeclaredFromExtensions(theQuestionnaire.getModifierExtension());
		collectDeclaredFromItems(theQuestionnaire.getItem());
		List<Resource> contained = theQuestionnaire.getContained();
		for (Resource resource : contained) {
			if (resource instanceof DomainResource domain) {
				collectDeclaredFromExtensions(domain.getExtension());
				collectDeclaredFromExtensions(domain.getModifierExtension());
			}
		}
	}

	private void collectLinkIds(List<Questionnaire.QuestionnaireItemComponent> theItems) {
		for (Questionnaire.QuestionnaireItemComponent item : theItems) {
			if (item.hasLinkId()) {
				myLinkIds.add(item.getLinkId());
			}
			collectLinkIds(item.getItem());
		}
	}

	private void collectDeclaredFromItems(List<Questionnaire.QuestionnaireItemComponent> theItems) {
		for (Questionnaire.QuestionnaireItemComponent item : theItems) {
			collectDeclaredFromExtensions(item.getExtension());
			collectDeclaredFromExtensions(item.getModifierExtension());
			for (Questionnaire.QuestionnaireItemAnswerOptionComponent option : item.getAnswerOption()) {
				collectDeclaredFromExtensions(option.getExtension());
			}
			for (Questionnaire.QuestionnaireItemInitialComponent initial : item.getInitial()) {
				collectDeclaredFromExtensions(initial.getExtension());
			}
			for (Questionnaire.QuestionnaireItemEnableWhenComponent enableWhen : item.getEnableWhen()) {
				collectDeclaredFromExtensions(enableWhen.getExtension());
			}
			collectDeclaredFromItems(item.getItem());
		}
	}

	private void collectDeclaredFromExtensions(List<Extension> theExtensions) {
		for (Extension extension : theExtensions) {
			String url = extension.getUrl();
			if (URL_VARIABLE.equals(url) && extension.getValue() instanceof Expression expression && expression.hasName()) {
				myDeclaredEnv.add(expression.getName());
			}
			if (URL_LAUNCH_CONTEXT.equals(url)) {
				for (Extension child : extension.getExtension()) {
					if (isRelativeSlice(child.getUrl(), "name") && child.getValue() instanceof Coding coding && coding.hasCode()) {
						myDeclaredEnv.add(coding.getCode());
					}
				}
			}
			if (URL_ASSEMBLE_CONTEXT.equals(url)) {
				addPrimitiveName(extension.getValue());
			}
			if (URL_EXTRACT_ALLOCATE_ID.equals(url)) {
				addPrimitiveName(extension.getValue());
			}
			if (extension.getValue() instanceof Expression expression && expression.hasName()) {
				myDeclaredEnv.add(expression.getName());
			}
			collectDeclaredFromExtensions(extension.getExtension());
		}
	}

	private void addPrimitiveName(Type theValue) {
		if (theValue instanceof StringType stringType && stringType.hasValue()) {
			myDeclaredEnv.add(stringType.getValue());
		} else if (theValue instanceof IdType idType && idType.hasValue()) {
			myDeclaredEnv.add(idType.getValue());
		} else if (theValue instanceof CodeType codeType && codeType.hasValue()) {
			myDeclaredEnv.add(codeType.getValue());
		} else if (theValue instanceof UriType uriType && uriType.hasValue()) {
			myDeclaredEnv.add(uriType.getValue());
		}
	}

	// ---------------------------------------------------------- walk

	private void walkQuestionnaire(Questionnaire theQuestionnaire) {
		walkExtensions(theQuestionnaire.getExtension(), "Questionnaire", "extension");
		walkExtensions(theQuestionnaire.getModifierExtension(), "Questionnaire", "modifierExtension");
		walkItems(theQuestionnaire.getItem(), "Questionnaire");
		List<Resource> contained = theQuestionnaire.getContained();
		for (int i = 0; i < contained.size(); i++) {
			Resource resource = contained.get(i);
			if (resource instanceof DomainResource domain) {
				String base = "Questionnaire.contained[" + i + "]";
				walkExtensions(domain.getExtension(), base, "extension");
				walkExtensions(domain.getModifierExtension(), base, "modifierExtension");
			}
		}
	}

	private void walkItems(List<Questionnaire.QuestionnaireItemComponent> theItems, String theParentPath) {
		for (int i = 0; i < theItems.size(); i++) {
			Questionnaire.QuestionnaireItemComponent item = theItems.get(i);
			String path = theParentPath + ".item[" + i + "]";
			walkExtensions(item.getExtension(), path, "extension");
			walkExtensions(item.getModifierExtension(), path, "modifierExtension");
			walkBackboneExtensions(item.getAnswerOption(), path, "answerOption");
			walkBackboneExtensions(item.getInitial(), path, "initial");
			walkBackboneExtensions(item.getEnableWhen(), path, "enableWhen");
			walkBackboneExtensions(item.getCode(), path, "code");
			walkItems(item.getItem(), path);
		}
	}

	private void walkBackboneExtensions(List<? extends Base> theElements, String theParentPath, String theField) {
		for (int i = 0; i < theElements.size(); i++) {
			Base element = theElements.get(i);
			if (element instanceof org.hl7.fhir.r4.model.Element typed) {
				walkExtensions(typed.getExtension(), theParentPath + "." + theField + "[" + i + "]", "extension");
			}
		}
	}

	private void walkExtensions(List<Extension> theExtensions, String theParentPath, String theField) {
		for (int i = 0; i < theExtensions.size(); i++) {
			handleExtension(theExtensions.get(i), theParentPath + "." + theField + "[" + i + "]", null);
		}
	}

	private void handleExtension(Extension theExtension, String thePath, String theParentUrl) {
		String url = theExtension.getUrl() == null ? "" : theExtension.getUrl();
		Type value = theExtension.getValue();
		if (value instanceof Expression expression) {
			boolean variableSite = URL_VARIABLE.equals(url);
			handleExpression(expression, url, thePath + ".valueExpression", variableSite);
		} else if (value instanceof StringType stringType && isImpliedFhirPathString(url, theParentUrl)) {
			handleFhirPathString(stringType.getValue(), labelUrl(url, theParentUrl), thePath + ".valueString");
		}

		List<Extension> nested = theExtension.getExtension();
		for (int i = 0; i < nested.size(); i++) {
			handleExtension(nested.get(i), thePath + ".extension[" + i + "]", url);
		}
	}

	// ---------------------------------------------------------- checks

	private void handleExpression(Expression theExpression, String theUrl, String theValuePath, boolean theVariableSite) {
		String language = theExpression.hasLanguage() ? theExpression.getLanguage().trim() : "";
		if (!language.isEmpty() && !LANG_FHIRPATH.equals(language)) {
			addIssue(
					ResultSeverityEnum.INFORMATION,
					"Expression language '" + language + "' is not validated at authoring time",
					theValuePath + ".language",
					language);
			return;
		}

		boolean hasExpression = theExpression.hasExpression() && !theExpression.getExpression().isBlank();
		if (!hasExpression) {
			if (!theExpression.hasReference()) {
				addIssue(
						ResultSeverityEnum.ERROR,
						"Missing FHIRPath in " + shortName(theUrl),
						theValuePath + ".expression",
						null);
			}
			return;
		}

		String expr = theExpression.getExpression();
		String label = shortName(theUrl);
		String exprPath = theValuePath + ".expression";
		parseFhirPath(expr, label, exprPath);

		if (theVariableSite && theExpression.hasName()) {
			checkVariableSelfReference(theExpression.getName(), expr, exprPath);
		}
		checkUnknownLinkIds(expr, exprPath, theUrl);
		checkUndeclaredEnv(expr, exprPath);
	}

	private void handleFhirPathString(String theExpr, String theUrl, String thePath) {
		if (theExpr == null || theExpr.isBlank()) {
			addIssue(ResultSeverityEnum.ERROR, "Missing FHIRPath in " + shortName(theUrl), thePath, theExpr);
			return;
		}
		parseFhirPath(theExpr, shortName(theUrl), thePath);
		checkUnknownLinkIds(theExpr, thePath, theUrl);
		checkUndeclaredEnv(theExpr, thePath);
	}

	private void parseFhirPath(String theExpr, String theLabel, String thePath) {
		try {
			myFhirPath.parse(theExpr);
		} catch (Throwable t) {
			String detail = t.getMessage();
			if (detail == null || detail.isBlank()) {
				detail = t.getClass().getSimpleName();
			}
			addIssue(ResultSeverityEnum.ERROR, "Invalid FHIRPath in " + theLabel + ": " + detail, thePath, theExpr);
		}
	}

	private void checkVariableSelfReference(String theName, String theExpr, String thePath) {
		Pattern self = Pattern.compile("%" + Pattern.quote(theName) + "(?![A-Za-z0-9_])");
		if (self.matcher(theExpr).find()) {
			addIssue(
					ResultSeverityEnum.ERROR,
					"Variable '" + theName + "' refers to itself in its FHIRPath expression",
					thePath,
					theExpr);
		}
	}

	private void checkUnknownLinkIds(String theExpr, String thePath, String theExtensionUrl) {
		ResultSeverityEnum severity = escalateUnknownLinkId(theExtensionUrl)
				? ResultSeverityEnum.ERROR
				: ResultSeverityEnum.WARNING;
		Matcher matcher = LINK_ID_LITERAL.matcher(theExpr);
		Set<String> seen = new LinkedHashSet<>();
		while (matcher.find()) {
			String linkId = matcher.group(1);
			if (seen.add(linkId) && !myLinkIds.contains(linkId)) {
				addIssue(
						severity,
						"FHIRPath references unknown linkId '" + linkId + "'",
						thePath,
						theExpr);
			}
		}
	}

	static boolean escalateUnknownLinkId(String theExtensionUrl) {
		return theExtensionUrl != null && ESCALATED_LINK_ID_URLS.contains(theExtensionUrl);
	}

	private void checkUndeclaredEnv(String theExpr, String thePath) {
		Matcher matcher = ENV_VAR.matcher(theExpr);
		Set<String> seen = new LinkedHashSet<>();
		while (matcher.find()) {
			String name = matcher.group(1);
			if (seen.add(name) && !myDeclaredEnv.contains(name)) {
				addIssue(
						ResultSeverityEnum.WARNING,
						"FHIRPath references undeclared environment variable '%" + name + "'",
						thePath,
						theExpr);
			}
		}
	}

	// ---------------------------------------------------------- location / helpers

	private void addIssue(ResultSeverityEnum theSeverity, String theMessage, String thePath, String theValue) {
		ValidationIssueDto dto = new ValidationIssueDto();
		dto.setSeverity(theSeverity.getCode());
		dto.setMessage(theMessage);
		JsonElementLocator.Occurrence occurrence = myLocator.locate(thePath);
		if (occurrence == null && theValue != null) {
			occurrence = myLocator.locateByValueText(theValue);
		}
		if (occurrence != null) {
			dto.setLine(occurrence.line());
			dto.setColumn(occurrence.column());
			dto.setLocation(thePath);
		} else {
			dto.setLine(1);
			dto.setColumn(1);
			dto.setLocation(thePath == null || thePath.isBlank() ? "Questionnaire" : thePath);
		}
		myIssues.add(dto);
	}

	private static boolean isImpliedFhirPathString(String theUrl, String theParentUrl) {
		if (URL_TEMPLATE_EXTRACT_VALUE.equals(theUrl) || URL_TEMPLATE_EXTRACT_CONTEXT.equals(theUrl)) {
			return true;
		}
		if (theParentUrl != null && URL_CONSTRAINT.equals(theParentUrl) && isRelativeSlice(theUrl, "expression")) {
			return true;
		}
		if (theParentUrl != null && EXTRACT_PARENT_URLS.contains(theParentUrl)) {
			return EXTRACT_SLICE_NAMES.contains(sliceName(theUrl));
		}
		return false;
	}

	private static boolean isRelativeSlice(String theUrl, String theSlice) {
		if (theUrl == null || theUrl.isBlank()) {
			return false;
		}
		return theSlice.equals(theUrl)
				|| theUrl.endsWith("/" + theSlice)
				|| theUrl.endsWith("#" + theSlice);
	}

	private static String sliceName(String theUrl) {
		if (theUrl == null) {
			return "";
		}
		int hash = theUrl.lastIndexOf('#');
		if (hash >= 0 && hash < theUrl.length() - 1) {
			return theUrl.substring(hash + 1);
		}
		int slash = theUrl.lastIndexOf('/');
		return slash >= 0 ? theUrl.substring(slash + 1) : theUrl;
	}

	private static String labelUrl(String theUrl, String theParentUrl) {
		if (theUrl != null && theUrl.contains("StructureDefinition/")) {
			return theUrl;
		}
		if (theParentUrl != null && !theParentUrl.isBlank()) {
			return theParentUrl;
		}
		return theUrl;
	}

	static String shortName(String theUrl) {
		if (theUrl == null || theUrl.isBlank()) {
			return "expression";
		}
		String last = sliceName(theUrl);
		if (last.startsWith("sdc-questionnaire-")) {
			return last.substring("sdc-questionnaire-".length());
		}
		if (last.startsWith("questionnaire-")) {
			return last.substring("questionnaire-".length());
		}
		return last;
	}

	/** Visible for tests: environment names collected from a Questionnaire. */
	static Set<String> declaredEnvironmentNames(Questionnaire theQuestionnaire) {
		ExpressionFhirPathValidator validator =
				new ExpressionFhirPathValidator(FhirContext.forR4(), "{}");
		validator.collectContext(theQuestionnaire);
		return Set.copyOf(validator.myDeclaredEnv);
	}

	static boolean isSkippedLanguage(String theLanguage) {
		if (theLanguage == null) {
			return false;
		}
		String language = theLanguage.trim().toLowerCase(Locale.ROOT);
		return LANG_CQL.equals(language) || LANG_FHIR_QUERY.equals(language);
	}
}
