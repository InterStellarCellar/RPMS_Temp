package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Type;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Authoring-time checks for SDC observation extract / populate on a Questionnaire.
 *
 * <p>Extension-triggered: rules fire when {@code observationExtract},
 * {@code observationLinkPeriod}, {@code unitOption}, {@code unitValueSet}, or
 * {@code observationExtractCategory} appear (or extract is inherited). Plain
 * Questionnaires without these extensions stay silent.
 *
 * <p>Does not execute {@code $extract} / populate against a QuestionnaireResponse.
 */
class ObservationExtractValidator {

	static final String URL_OBSERVATION_EXTRACT =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtract";
	static final String URL_OBSERVATION_LINK_PERIOD =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationLinkPeriod";
	static final String URL_OBSERVATION_EXTRACT_CATEGORY =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-observationExtractCategory";
	static final String URL_UNIT = "http://hl7.org/fhir/StructureDefinition/questionnaire-unit";
	static final String URL_UNIT_OPTION = "http://hl7.org/fhir/StructureDefinition/questionnaire-unitOption";
	static final String URL_UNIT_VALUE_SET = "http://hl7.org/fhir/StructureDefinition/questionnaire-unitValueSet";

	static final String MSG_EXTRACT_REQUIRES_CODE = "observationExtract requires item.code";
	static final String MSG_LINK_PERIOD_REQUIRES_CODE = "observationLinkPeriod requires item.code";
	static final String MSG_NUMERIC_REQUIRES_UNIT = "extractable numeric item requires a unit";
	static final String MSG_UNIT_OPTION_QUANTITY_ONLY = "unitOption is only valid on quantity";
	static final String MSG_UNIT_VALUE_SET_QUANTITY_ONLY = "unitValueSet is only valid on quantity";
	static final String MSG_CATEGORY_RECOMMENDED = "observationExtractCategory is recommended";
	static final String MSG_PANEL_REQUIRES_CODE = "observation extract component/member requires item.code";
	static final String MSG_PANEL_DISTINCT_CODES = "observation extract component/member requires distinct codes";

	private static final class ExtractState {
		private final boolean extracting;
		/** Local relationship code ({@code component}, {@code member}, …); never inherited. */
		private final String relationship;
		private final boolean hasCategory;

		private ExtractState(boolean theExtracting, String theRelationship, boolean theHasCategory) {
			extracting = theExtracting;
			relationship = theRelationship;
			hasCategory = theHasCategory;
		}
	}

	private final JsonElementLocator myLocator;
	private final List<ValidationIssueDto> myIssues = new ArrayList<>();

	private ObservationExtractValidator(String theJson) {
		myLocator = new JsonElementLocator(theJson);
	}

	static List<ValidationIssueDto> validate(Questionnaire theQuestionnaire, String theJson) {
		ObservationExtractValidator validator = new ObservationExtractValidator(theJson);
		validator.walkQuestionnaire(theQuestionnaire);
		return validator.myIssues;
	}

	private void walkQuestionnaire(Questionnaire theQuestionnaire) {
		ExtractState root = resolve(theQuestionnaire.getExtension(), new ExtractState(false, null, false));
		if (root.extracting && !root.hasCategory && firstExt(theQuestionnaire.getExtension(), URL_OBSERVATION_EXTRACT) != null) {
			addIssue(
					ResultSeverityEnum.WARNING,
					MSG_CATEGORY_RECOMMENDED,
					extensionPath("Questionnaire", theQuestionnaire.getExtension(), URL_OBSERVATION_EXTRACT),
					URL_OBSERVATION_EXTRACT);
		}
		walkItems(theQuestionnaire.getItem(), "Questionnaire", root);
	}

	private void walkItems(
			List<Questionnaire.QuestionnaireItemComponent> theItems, String theParentPath, ExtractState theInherited) {
		for (int i = 0; i < theItems.size(); i++) {
			walkItem(theItems.get(i), theParentPath + ".item[" + i + "]", theInherited);
		}
	}

	private void walkItem(Questionnaire.QuestionnaireItemComponent theItem, String thePath, ExtractState theInherited) {
		ExtractState state = resolve(theItem.getExtension(), theInherited);
		applyItemRules(theItem, thePath, state);
		applyPanelRules(theItem, thePath, state);
		walkItems(theItem.getItem(), thePath, state);
	}

	private void applyItemRules(
			Questionnaire.QuestionnaireItemComponent theItem, String thePath, ExtractState theState) {
		boolean valueProducing = isValueProducing(theItem);
		boolean hasCode = hasUsableCode(theItem);

		if (theState.extracting && valueProducing && !hasCode) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_EXTRACT_REQUIRES_CODE,
					codeOrExtractPath(theItem, thePath),
					URL_OBSERVATION_EXTRACT);
		}

		if (firstExt(theItem.getExtension(), URL_OBSERVATION_LINK_PERIOD) != null && !hasCode) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_LINK_PERIOD_REQUIRES_CODE,
					extensionPath(thePath, theItem.getExtension(), URL_OBSERVATION_LINK_PERIOD),
					URL_OBSERVATION_LINK_PERIOD);
		}

		if (theState.extracting && isNumeric(theItem) && !hasUnit(theItem)) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_NUMERIC_REQUIRES_UNIT,
					extractOrItemPath(theItem, thePath),
					URL_OBSERVATION_EXTRACT);
		}

		if (firstExt(theItem.getExtension(), URL_UNIT_OPTION) != null && !isQuantity(theItem)) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_UNIT_OPTION_QUANTITY_ONLY,
					extensionPath(thePath, theItem.getExtension(), URL_UNIT_OPTION),
					URL_UNIT_OPTION);
		}
		if (firstExt(theItem.getExtension(), URL_UNIT_VALUE_SET) != null && !isQuantity(theItem)) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_UNIT_VALUE_SET_QUANTITY_ONLY,
					extensionPath(thePath, theItem.getExtension(), URL_UNIT_VALUE_SET),
					URL_UNIT_VALUE_SET);
		}

		boolean localExtract = firstExt(theItem.getExtension(), URL_OBSERVATION_EXTRACT) != null;
		if (theState.extracting && !theState.hasCategory && localExtract) {
			addIssue(
					ResultSeverityEnum.WARNING,
					MSG_CATEGORY_RECOMMENDED,
					extensionPath(thePath, theItem.getExtension(), URL_OBSERVATION_EXTRACT),
					URL_OBSERVATION_EXTRACT);
		}
	}

	private void applyPanelRules(
			Questionnaire.QuestionnaireItemComponent theItem, String thePath, ExtractState theState) {
		List<Questionnaire.QuestionnaireItemComponent> children = theItem.getItem();
		if (children.isEmpty()) {
			return;
		}
		boolean childHasRelationship = false;
		for (Questionnaire.QuestionnaireItemComponent child : children) {
			if (resolve(child.getExtension(), theState).relationship != null) {
				childHasRelationship = true;
				break;
			}
		}
		if (theState.relationship == null && !childHasRelationship) {
			return;
		}

		if (!hasUsableCode(theItem)) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_PANEL_REQUIRES_CODE,
					codeOrExtractPath(theItem, thePath),
					URL_OBSERVATION_EXTRACT);
		}
		Set<String> parentCodes = usableCodeTokens(theItem);
		for (int i = 0; i < children.size(); i++) {
			Questionnaire.QuestionnaireItemComponent child = children.get(i);
			ExtractState childState = resolve(child.getExtension(), theState);
			if (!childState.extracting || isDisplay(child)) {
				continue;
			}
			String childPath = thePath + ".item[" + i + "]";
			if (!hasUsableCode(child)) {
				if (isValueProducing(child)) {
					// Rule 1 already reported missing code on the child.
					continue;
				}
				addIssue(
						ResultSeverityEnum.ERROR,
						MSG_PANEL_REQUIRES_CODE,
						codeOrExtractPath(child, childPath),
						URL_OBSERVATION_EXTRACT);
				continue;
			}
			for (String token : usableCodeTokens(child)) {
				if (parentCodes.contains(token)) {
					addIssue(
							ResultSeverityEnum.ERROR,
							MSG_PANEL_DISTINCT_CODES,
							childPath + ".code[0]",
							token);
				}
			}
		}
	}

	private ExtractState resolve(List<Extension> theExtensions, ExtractState theInherited) {
		boolean hasCategory = theInherited.hasCategory || firstExt(theExtensions, URL_OBSERVATION_EXTRACT_CATEGORY) != null;
		Extension extract = firstExt(theExtensions, URL_OBSERVATION_EXTRACT);
		if (extract == null) {
			return new ExtractState(theInherited.extracting, null, hasCategory);
		}
		Type value = extract.getValue();
		if (value instanceof BooleanType booleanType && booleanType.hasValue()) {
			return new ExtractState(booleanType.booleanValue(), null, hasCategory);
		}
		if (value instanceof CodeType codeType && codeType.hasValue() && !codeType.getValue().isBlank()) {
			return new ExtractState(true, codeType.getValue().trim(), hasCategory);
		}
		if (value instanceof Coding coding && coding.hasCode() && !coding.getCode().isBlank()) {
			return new ExtractState(true, coding.getCode().trim(), hasCategory);
		}
		return new ExtractState(true, null, hasCategory);
	}

	private static boolean hasUsableCode(Questionnaire.QuestionnaireItemComponent theItem) {
		return !usableCodeTokens(theItem).isEmpty();
	}

	private static Set<String> usableCodeTokens(Questionnaire.QuestionnaireItemComponent theItem) {
		Set<String> tokens = new LinkedHashSet<>();
		for (Coding coding : theItem.getCode()) {
			if (coding == null) {
				continue;
			}
			String system = coding.getSystem();
			String code = coding.getCode();
			if (system == null || system.isBlank() || code == null || code.isBlank()) {
				continue;
			}
			tokens.add(system.trim().toLowerCase(Locale.ROOT) + "#" + code.trim());
		}
		return tokens;
	}

	private static boolean hasUnit(Questionnaire.QuestionnaireItemComponent theItem) {
		List<Extension> extensions = theItem.getExtension();
		return firstExt(extensions, URL_UNIT) != null
				|| firstExt(extensions, URL_UNIT_OPTION) != null
				|| firstExt(extensions, URL_UNIT_VALUE_SET) != null;
	}

	private static boolean isValueProducing(Questionnaire.QuestionnaireItemComponent theItem) {
		return !isGroup(theItem) && !isDisplay(theItem);
	}

	private static boolean isNumeric(Questionnaire.QuestionnaireItemComponent theItem) {
		Questionnaire.QuestionnaireItemType type = theItem.getType();
		return type == Questionnaire.QuestionnaireItemType.INTEGER
				|| type == Questionnaire.QuestionnaireItemType.DECIMAL
				|| type == Questionnaire.QuestionnaireItemType.QUANTITY;
	}

	private static boolean isQuantity(Questionnaire.QuestionnaireItemComponent theItem) {
		return theItem.getType() == Questionnaire.QuestionnaireItemType.QUANTITY;
	}

	private static boolean isGroup(Questionnaire.QuestionnaireItemComponent theItem) {
		return theItem.getType() == Questionnaire.QuestionnaireItemType.GROUP;
	}

	private static boolean isDisplay(Questionnaire.QuestionnaireItemComponent theItem) {
		return theItem.getType() == Questionnaire.QuestionnaireItemType.DISPLAY;
	}

	private static Extension firstExt(List<Extension> theExtensions, String theUrl) {
		for (Extension extension : theExtensions) {
			if (theUrl.equals(extension.getUrl())) {
				return extension;
			}
		}
		return null;
	}

	private static int extensionIndex(List<Extension> theExtensions, String theUrl) {
		for (int i = 0; i < theExtensions.size(); i++) {
			if (theUrl.equals(theExtensions.get(i).getUrl())) {
				return i;
			}
		}
		return -1;
	}

	private static String extensionPath(String theParentPath, List<Extension> theExtensions, String theUrl) {
		int index = extensionIndex(theExtensions, theUrl);
		if (index >= 0) {
			return theParentPath + ".extension[" + index + "]";
		}
		return theParentPath;
	}

	private static String codeOrExtractPath(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
		if (theItem.hasCode() && !theItem.getCode().isEmpty()) {
			return thePath + ".code[0]";
		}
		return extractOrItemPath(theItem, thePath);
	}

	private static String extractOrItemPath(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
		int index = extensionIndex(theItem.getExtension(), URL_OBSERVATION_EXTRACT);
		if (index >= 0) {
			return thePath + ".extension[" + index + "]";
		}
		if (theItem.hasLinkId()) {
			return thePath + ".linkId";
		}
		return thePath;
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
