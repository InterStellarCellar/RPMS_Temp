package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.TimeType;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.UriType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoring-time type-agreement checks: {@code answerOption} / {@code initial} /
 * {@code enableWhen.answer[x]} / {@code minValue}/{@code maxValue} vs {@code item.type},
 * and multiple {@code initialSelected} without {@code repeats}.
 *
 * <p>Always-on (no profile gate). FHIR detailed descriptions require answer/initial
 * value types to agree with {@code item.type}; HAPI does not enforce this as errors.
 */
class TypeConsistencyValidator {

	static final String URL_MIN_VALUE = "http://hl7.org/fhir/StructureDefinition/minValue";
	static final String URL_MAX_VALUE = "http://hl7.org/fhir/StructureDefinition/maxValue";

	static final String MSG_ANSWER_OPTION_TYPE = "answerOption value type does not match item.type";
	static final String MSG_INITIAL_TYPE = "initial value type does not match item.type";
	static final String MSG_ENABLE_WHEN_ANSWER_TYPE =
			"enableWhen answer type does not match question item.type";
	static final String MSG_MIN_MAX_TYPE = "minValue/maxValue type does not match item.type";
	static final String MSG_INITIAL_SELECTED = "multiple initialSelected requires repeats";

	private static final class ItemSite {
		private final Questionnaire.QuestionnaireItemComponent item;
		private final String path;

		private ItemSite(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
			item = theItem;
			path = thePath;
		}
	}

	private final JsonElementLocator myLocator;
	private final List<ValidationIssueDto> myIssues = new ArrayList<>();
	private final List<ItemSite> mySites = new ArrayList<>();
	private final Map<String, ItemSite> myByLinkId = new LinkedHashMap<>();

	private TypeConsistencyValidator(String theJson) {
		myLocator = new JsonElementLocator(theJson);
	}

	static List<ValidationIssueDto> validate(Questionnaire theQuestionnaire, String theJson) {
		TypeConsistencyValidator validator = new TypeConsistencyValidator(theJson);
		validator.walkItems(theQuestionnaire.getItem(), "Questionnaire");
		validator.applyRules();
		return validator.myIssues;
	}

	private void walkItems(List<Questionnaire.QuestionnaireItemComponent> theItems, String theParentPath) {
		for (int i = 0; i < theItems.size(); i++) {
			walkItem(theItems.get(i), theParentPath + ".item[" + i + "]");
		}
	}

	private void walkItem(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
		ItemSite site = new ItemSite(theItem, thePath);
		mySites.add(site);
		if (theItem.hasLinkId() && !theItem.getLinkId().isBlank()) {
			myByLinkId.putIfAbsent(theItem.getLinkId(), site);
		}
		walkItems(theItem.getItem(), thePath);
	}

	private void applyRules() {
		for (ItemSite site : mySites) {
			applyAnswerOptionTypes(site);
			applyInitialTypes(site);
			applyEnableWhenAnswerTypes(site);
			applyMinMaxTypes(site);
			applyInitialSelected(site);
		}
	}

	private void applyAnswerOptionTypes(ItemSite theSite) {
		List<Questionnaire.QuestionnaireItemAnswerOptionComponent> options = theSite.item.getAnswerOption();
		for (int i = 0; i < options.size(); i++) {
			Type value = options.get(i).getValue();
			if (value == null) {
				continue;
			}
			if (!valueMatchesItemType(value, theSite.item.getType(), true)) {
				addIssue(
						ResultSeverityEnum.ERROR,
						MSG_ANSWER_OPTION_TYPE,
						theSite.path + ".answerOption[" + i + "]",
						value.fhirType());
			}
		}
	}

	private void applyInitialTypes(ItemSite theSite) {
		List<Questionnaire.QuestionnaireItemInitialComponent> initials = theSite.item.getInitial();
		for (int i = 0; i < initials.size(); i++) {
			Type value = initials.get(i).getValue();
			if (value == null) {
				continue;
			}
			if (!valueMatchesItemType(value, theSite.item.getType(), false)) {
				addIssue(
						ResultSeverityEnum.ERROR,
						MSG_INITIAL_TYPE,
						theSite.path + ".initial[" + i + "]",
						value.fhirType());
			}
		}
	}

	private void applyEnableWhenAnswerTypes(ItemSite theSite) {
		List<Questionnaire.QuestionnaireItemEnableWhenComponent> enableWhens = theSite.item.getEnableWhen();
		for (int i = 0; i < enableWhens.size(); i++) {
			Questionnaire.QuestionnaireItemEnableWhenComponent ew = enableWhens.get(i);
			if (ew.getOperator() == Questionnaire.QuestionnaireItemOperator.EXISTS) {
				continue;
			}
			if (!ew.hasQuestion() || ew.getQuestion().isBlank()) {
				continue;
			}
			ItemSite target = myByLinkId.get(ew.getQuestion());
			if (target == null || !target.item.hasType()) {
				continue;
			}
			Type answer = ew.getAnswer();
			if (answer == null) {
				continue;
			}
			if (!valueMatchesItemType(answer, target.item.getType(), false)) {
				addIssue(
						ResultSeverityEnum.ERROR,
						MSG_ENABLE_WHEN_ANSWER_TYPE,
						theSite.path + ".enableWhen[" + i + "]",
						answer.fhirType());
			}
		}
	}

	private void applyMinMaxTypes(ItemSite theSite) {
		checkBoundExt(theSite, URL_MIN_VALUE);
		checkBoundExt(theSite, URL_MAX_VALUE);
	}

	private void checkBoundExt(ItemSite theSite, String theUrl) {
		List<Extension> extensions = theSite.item.getExtension();
		for (int i = 0; i < extensions.size(); i++) {
			Extension extension = extensions.get(i);
			if (!theUrl.equals(extension.getUrl())) {
				continue;
			}
			Type value = extension.getValue();
			if (value == null || !theSite.item.hasType()) {
				continue;
			}
			if (!boundMatchesItemType(value, theSite.item.getType())) {
				addIssue(
						ResultSeverityEnum.ERROR,
						MSG_MIN_MAX_TYPE,
						theSite.path + ".extension[" + i + "]",
						theUrl);
			}
		}
	}

	private void applyInitialSelected(ItemSite theSite) {
		if (theSite.item.getRepeats()) {
			return;
		}
		int selected = 0;
		List<Questionnaire.QuestionnaireItemAnswerOptionComponent> options = theSite.item.getAnswerOption();
		for (Questionnaire.QuestionnaireItemAnswerOptionComponent option : options) {
			if (option.hasInitialSelected() && option.getInitialSelected()) {
				selected++;
			}
		}
		if (selected > 1) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_INITIAL_SELECTED,
					theSite.path + ".answerOption",
					"initialSelected");
		}
	}

	/**
	 * @param theAnswerOptionContext when true, open-choice also allows string answerOption values
	 */
	static boolean valueMatchesItemType(
			Type theValue, Questionnaire.QuestionnaireItemType theItemType, boolean theAnswerOptionContext) {
		if (theValue == null || theItemType == null) {
			return true;
		}
		String fhirType = theValue.fhirType();
		return switch (theItemType) {
			case BOOLEAN -> theValue instanceof BooleanType;
			case INTEGER -> theValue instanceof IntegerType;
			case DECIMAL -> theValue instanceof DecimalType;
			case DATE -> theValue instanceof DateType;
			case DATETIME -> theValue instanceof DateTimeType;
			case TIME -> theValue instanceof TimeType;
			case STRING, TEXT -> theValue instanceof StringType;
			case URL -> theValue instanceof StringType || theValue instanceof UriType;
			// choice / open-choice options may use any answerOption.value[x] shape
			// (integer|date|time|string|Coding|Reference) per the R4 element definition.
			case CHOICE, OPENCHOICE -> isAnswerOptionValueShape(theValue)
					|| (!theAnswerOptionContext && theValue instanceof Coding);
			case QUANTITY -> theValue instanceof Quantity;
			case REFERENCE -> theValue instanceof Reference;
			case ATTACHMENT -> "Attachment".equals(fhirType);
			case GROUP, DISPLAY, NULL -> true;
			default -> true;
		};
	}

	/** R4 Questionnaire.item.answerOption.value[x] allowed shapes. */
	private static boolean isAnswerOptionValueShape(Type theValue) {
		return theValue instanceof IntegerType
				|| theValue instanceof DateType
				|| theValue instanceof TimeType
				|| theValue instanceof StringType
				|| theValue instanceof Coding
				|| theValue instanceof Reference;
	}

	static boolean boundMatchesItemType(Type theValue, Questionnaire.QuestionnaireItemType theItemType) {
		if (theValue == null || theItemType == null) {
			return true;
		}
		return switch (theItemType) {
			case INTEGER -> theValue instanceof IntegerType;
			case DECIMAL -> theValue instanceof DecimalType || theValue instanceof IntegerType;
			case QUANTITY -> theValue instanceof Quantity
					|| theValue instanceof DecimalType
					|| theValue instanceof IntegerType;
			case DATE -> theValue instanceof DateType;
			case DATETIME -> theValue instanceof DateTimeType || theValue instanceof DateType;
			case TIME -> theValue instanceof TimeType;
			// Bounds are not meaningful on other item types; any bound value is a mismatch.
			default -> false;
		};
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
