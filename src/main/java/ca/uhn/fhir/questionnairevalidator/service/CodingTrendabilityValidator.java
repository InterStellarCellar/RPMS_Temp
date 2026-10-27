package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Expression;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Type;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authoring-time coding / terminology checks for trendable Questionnaire items.
 *
 * <p>Requiring {@code item.code} on value-producing items is gated on monitoring
 * intent (versioned {@code url}+{@code version}, observation extract / link-period
 * extensions, or a scoring {@code calculatedExpression}). Incomplete and example
 * Codings are always checked when present. Does not look up codes on a terminology
 * server.
 */
class CodingTrendabilityValidator {

	static final String URL_CALCULATED_EXPRESSION =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression";
	static final String URL_ORDINAL_VALUE = "http://hl7.org/fhir/StructureDefinition/ordinalValue";
	static final String URL_ITEM_WEIGHT = "http://hl7.org/fhir/StructureDefinition/itemWeight";
	static final String URL_SDC_ITEM_WEIGHT =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-itemWeight";
	static final String URL_OBSERVATION_EXTRACT =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract";
	static final String URL_OBSERVATION_LINK_PERIOD =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationLinkPeriod";
	static final String URL_OBSERVATION_EXTRACT_CATEGORY =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtractCategory";

	static final String MSG_MISSING_ITEM_CODE = "value-producing item requires item.code";
	static final String MSG_CODING_INCOMPLETE = "Coding must include both system and code";
	static final String MSG_EXAMPLE_SYSTEM = "example CodeSystem is not a stable coded identity";
	static final String MSG_MISSING_ORDINAL = "scored choice answerOption requires ordinalValue or itemWeight";

	private static final Pattern LINK_ID_LITERAL =
			Pattern.compile("linkId\\s*=\\s*['\"]([^'\"]+)['\"]");

	private static final class ItemSite {
		private final Questionnaire.QuestionnaireItemComponent item;
		private final String path;
		private final String parentPath;

		private ItemSite(Questionnaire.QuestionnaireItemComponent theItem, String thePath, String theParentPath) {
			item = theItem;
			path = thePath;
			parentPath = theParentPath;
		}
	}

	private final JsonElementLocator myLocator;
	private final List<ValidationIssueDto> myIssues = new ArrayList<>();
	private final List<ItemSite> mySites = new ArrayList<>();
	private final Map<String, ItemSite> myByLinkId = new LinkedHashMap<>();
	private boolean myRequireItemCode;

	private CodingTrendabilityValidator(String theJson) {
		myLocator = new JsonElementLocator(theJson);
	}

	static List<ValidationIssueDto> validate(Questionnaire theQuestionnaire, String theJson) {
		CodingTrendabilityValidator validator = new CodingTrendabilityValidator(theJson);
		validator.myRequireItemCode = hasMonitoringIntent(theQuestionnaire);
		validator.walkItems(theQuestionnaire.getItem(), "Questionnaire");
		validator.applyOrdinalScoring();
		return validator.myIssues;
	}

	/**
	 * Monitoring / longitudinal authoring signals that make stable {@code item.code}
	 * mandatory on value-producing questions.
	 */
	static boolean hasMonitoringIntent(Questionnaire theQuestionnaire) {
		if (theQuestionnaire == null) {
			return false;
		}
		boolean hasUrl = theQuestionnaire.hasUrl() && !theQuestionnaire.getUrl().isBlank();
		boolean hasVersion = theQuestionnaire.hasVersion() && !theQuestionnaire.getVersion().isBlank();
		if (hasUrl && hasVersion) {
			return true;
		}
		if (hasMonitoringExtension(theQuestionnaire.getExtension())) {
			return true;
		}
		return hasMonitoringIntentInItems(theQuestionnaire.getItem());
	}

	private static boolean hasMonitoringIntentInItems(List<Questionnaire.QuestionnaireItemComponent> theItems) {
		for (Questionnaire.QuestionnaireItemComponent item : theItems) {
			if (item == null) {
				continue;
			}
			if (hasMonitoringExtension(item.getExtension())) {
				return true;
			}
			Expression expression = calculatedExpression(item);
			if (expression != null
					&& expression.hasExpression()
					&& !expression.getExpression().isBlank()) {
				String language = expression.hasLanguage() ? expression.getLanguage().trim() : "";
				if (language.isEmpty() || "text/fhirpath".equalsIgnoreCase(language)) {
					if (looksLikeOrdinalScoring(expression.getExpression())) {
						return true;
					}
				}
			}
			if (hasMonitoringIntentInItems(item.getItem())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasMonitoringExtension(List<Extension> theExtensions) {
		for (Extension extension : theExtensions) {
			if (extension == null) {
				continue;
			}
			String url = extension.getUrl();
			if (URL_OBSERVATION_EXTRACT.equals(url)
					|| URL_OBSERVATION_LINK_PERIOD.equals(url)
					|| URL_OBSERVATION_EXTRACT_CATEGORY.equals(url)) {
				return true;
			}
		}
		return false;
	}

	private void walkItems(List<Questionnaire.QuestionnaireItemComponent> theItems, String theParentPath) {
		for (int i = 0; i < theItems.size(); i++) {
			walkItem(theItems.get(i), theParentPath + ".item[" + i + "]", theParentPath);
		}
	}

	private void walkItem(Questionnaire.QuestionnaireItemComponent theItem, String thePath, String theParentPath) {
		ItemSite site = new ItemSite(theItem, thePath, theParentPath);
		mySites.add(site);
		if (theItem.hasLinkId() && !theItem.getLinkId().isBlank()) {
			myByLinkId.putIfAbsent(theItem.getLinkId(), site);
		}
		applyItemCodingRules(site);
		walkItems(theItem.getItem(), thePath);
	}

	private void applyItemCodingRules(ItemSite theSite) {
		Questionnaire.QuestionnaireItemComponent item = theSite.item;
		boolean valueProducing = isValueProducing(item);

		List<Coding> codes = item.getCode();
		boolean hasUsable = false;
		for (int i = 0; i < codes.size(); i++) {
			Coding coding = codes.get(i);
			if (coding == null) {
				continue;
			}
			String codingPath = theSite.path + ".code[" + i + "]";
			if (applyCodingShape(coding, codingPath)) {
				hasUsable = true;
			}
		}
		if (myRequireItemCode && valueProducing && !hasUsable) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_MISSING_ITEM_CODE,
					theSite.path + (item.hasLinkId() ? ".linkId" : ""),
					item.hasLinkId() ? item.getLinkId() : null);
		}

		List<Questionnaire.QuestionnaireItemAnswerOptionComponent> options = item.getAnswerOption();
		for (int i = 0; i < options.size(); i++) {
			Questionnaire.QuestionnaireItemAnswerOptionComponent option = options.get(i);
			if (option == null || !option.hasValueCoding()) {
				continue;
			}
			applyCodingShape(option.getValueCoding(), theSite.path + ".answerOption[" + i + "].valueCoding");
		}
	}

	/**
	 * @return true when the coding has both system and code (after reporting issues)
	 */
	private boolean applyCodingShape(Coding theCoding, String thePath) {
		String system = theCoding.getSystem();
		String code = theCoding.getCode();
		boolean blankSystem = system == null || system.isBlank();
		boolean blankCode = code == null || code.isBlank();
		if (blankSystem || blankCode) {
			addIssue(ResultSeverityEnum.ERROR, MSG_CODING_INCOMPLETE, thePath, blankCode ? system : code);
			return false;
		}
		if (isExampleSystem(system)) {
			addIssue(ResultSeverityEnum.ERROR, MSG_EXAMPLE_SYSTEM, thePath + ".system", system);
			return true;
		}
		return true;
	}

	private void applyOrdinalScoring() {
		for (ItemSite site : mySites) {
			Expression expression = calculatedExpression(site.item);
			if (expression == null) {
				continue;
			}
			String language = expression.hasLanguage() ? expression.getLanguage().trim() : "";
			if (!language.isEmpty() && !"text/fhirpath".equalsIgnoreCase(language)) {
				continue;
			}
			if (!expression.hasExpression() || expression.getExpression().isBlank()) {
				continue;
			}
			String expr = expression.getExpression();
			if (!looksLikeOrdinalScoring(expr)) {
				continue;
			}
			Set<String> referenced = extractLinkIds(expr);
			if (referenced.isEmpty()) {
				checkSiblingChoices(site);
			} else {
				for (String linkId : referenced) {
					ItemSite target = myByLinkId.get(linkId);
					if (target != null) {
						checkChoiceOrdinals(target);
					}
				}
			}
		}
	}

	private void checkSiblingChoices(ItemSite theCalcSite) {
		for (ItemSite site : mySites) {
			if (site == theCalcSite) {
				continue;
			}
			if (!theCalcSite.parentPath.equals(site.parentPath)) {
				continue;
			}
			checkChoiceOrdinals(site);
		}
	}

	private void checkChoiceOrdinals(ItemSite theSite) {
		if (!isChoice(theSite.item)) {
			return;
		}
		List<Questionnaire.QuestionnaireItemAnswerOptionComponent> options = theSite.item.getAnswerOption();
		if (options.isEmpty()) {
			return;
		}
		for (int i = 0; i < options.size(); i++) {
			Questionnaire.QuestionnaireItemAnswerOptionComponent option = options.get(i);
			if (option == null || hasOrdinalOrWeight(option)) {
				continue;
			}
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_MISSING_ORDINAL,
					theSite.path + ".answerOption[" + i + "]",
					theSite.item.hasLinkId() ? theSite.item.getLinkId() : null);
		}
	}

	private static boolean looksLikeOrdinalScoring(String theExpression) {
		if (ScoringExpressionHints.looksLikeOrdinalScoring(theExpression)) {
			return true;
		}
		if (theExpression == null || theExpression.isBlank()) {
			return false;
		}
		// Also treat linkId='…' references as scoring context for ordinal checks.
		return LINK_ID_LITERAL.matcher(theExpression).find();
	}

	private static Set<String> extractLinkIds(String theExpression) {
		Set<String> out = new LinkedHashSet<>();
		Matcher matcher = LINK_ID_LITERAL.matcher(theExpression);
		while (matcher.find()) {
			out.add(matcher.group(1));
		}
		return out;
	}

	private static Expression calculatedExpression(Questionnaire.QuestionnaireItemComponent theItem) {
		Extension extension = firstExt(theItem.getExtension(), URL_CALCULATED_EXPRESSION);
		if (extension == null) {
			return null;
		}
		Type value = extension.getValue();
		return value instanceof Expression expression ? expression : null;
	}

	private static boolean hasOrdinalOrWeight(Questionnaire.QuestionnaireItemAnswerOptionComponent theOption) {
		for (Extension extension : theOption.getExtension()) {
			String url = extension.getUrl();
			if (url == null) {
				continue;
			}
			if (URL_ORDINAL_VALUE.equals(url)
					|| URL_ITEM_WEIGHT.equals(url)
					|| URL_SDC_ITEM_WEIGHT.equals(url)
					|| url.endsWith("/ordinalValue")
					|| url.endsWith("/itemWeight")) {
				return true;
			}
		}
		return false;
	}

	static boolean isExampleSystem(String theSystem) {
		if (theSystem == null || theSystem.isBlank()) {
			return false;
		}
		String host = hostOf(theSystem);
		if (host == null || host.isBlank()) {
			String lower = theSystem.toLowerCase(Locale.ROOT);
			return lower.contains("example.org") || lower.contains("example.com");
		}
		String lower = host.toLowerCase(Locale.ROOT);
		return "example.org".equals(lower)
				|| lower.endsWith(".example.org")
				|| "example.com".equals(lower)
				|| lower.endsWith(".example.com");
	}

	private static String hostOf(String theSystem) {
		try {
			URI uri = URI.create(theSystem.trim());
			return uri.getHost();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean isValueProducing(Questionnaire.QuestionnaireItemComponent theItem) {
		Questionnaire.QuestionnaireItemType type = theItem.getType();
		return type != Questionnaire.QuestionnaireItemType.GROUP
				&& type != Questionnaire.QuestionnaireItemType.DISPLAY;
	}

	private static boolean isChoice(Questionnaire.QuestionnaireItemComponent theItem) {
		Questionnaire.QuestionnaireItemType type = theItem.getType();
		return type == Questionnaire.QuestionnaireItemType.CHOICE
				|| type == Questionnaire.QuestionnaireItemType.OPENCHOICE;
	}

	private static Extension firstExt(List<Extension> theExtensions, String theUrl) {
		for (Extension extension : theExtensions) {
			if (theUrl.equals(extension.getUrl())) {
				return extension;
			}
		}
		return null;
	}

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
}
