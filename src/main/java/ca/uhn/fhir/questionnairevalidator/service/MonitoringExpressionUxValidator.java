package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.Expression;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authoring-time monitoring UX checks for Questionnaire expressions and enableWhen
 * graphs: scoring calculatedExpression vs item.type, circular enableWhen, and
 * readOnly items without a value source.
 *
 * <p>Always-on (no profile gate). Does not evaluate FHIRPath against a
 * QuestionnaireResponse. Unknown {@code linkId='…'} escalation lives in
 * {@link ExpressionFhirPathValidator}; variable self-reference stays there too.
 */
class MonitoringExpressionUxValidator {

	static final String URL_CALCULATED_EXPRESSION =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-calculatedExpression";
	static final String URL_INITIAL_EXPRESSION =
			"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-initialExpression";

	static final String MSG_SCORING_TYPE =
			"calculatedExpression scoring requires item.type integer or decimal";
	static final String MSG_CIRCULAR_ENABLE_WHEN = "circular enableWhen dependency involving ";
	static final String MSG_READONLY_NO_SOURCE =
			"readOnly item has no initial, calculatedExpression, or initialExpression";
	static final String MSG_ENABLE_WHEN_DISPLAY = "enableWhen targets a display item";
	static final String MSG_ENABLE_WHEN_IMPOSSIBLE =
			"enableWhen conditions are impossible under enableBehavior all";

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
	/** linkId → enableWhen.question targets (order preserved). */
	private final Map<String, List<String>> myEnableWhenEdges = new LinkedHashMap<>();

	private MonitoringExpressionUxValidator(String theJson) {
		myLocator = new JsonElementLocator(theJson);
	}

	static List<ValidationIssueDto> validate(Questionnaire theQuestionnaire, String theJson) {
		MonitoringExpressionUxValidator validator = new MonitoringExpressionUxValidator(theJson);
		validator.walkItems(theQuestionnaire.getItem(), "Questionnaire");
		validator.applyScoringTypeRules();
		validator.applyCircularEnableWhen();
		validator.applyEnableWhenToDisplay();
		validator.applyImpossibleEnableWhenAll();
		validator.applyReadOnlyValueSource();
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
			List<String> targets = new ArrayList<>();
			for (Questionnaire.QuestionnaireItemEnableWhenComponent enableWhen : theItem.getEnableWhen()) {
				if (enableWhen.hasQuestion() && !enableWhen.getQuestion().isBlank()) {
					targets.add(enableWhen.getQuestion());
				}
			}
			if (!targets.isEmpty()) {
				myEnableWhenEdges.put(theItem.getLinkId(), targets);
			}
		}
		walkItems(theItem.getItem(), thePath);
	}

	private void applyScoringTypeRules() {
		for (ItemSite site : mySites) {
			Expression expression = expressionExt(site.item, URL_CALCULATED_EXPRESSION);
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
			if (!ScoringExpressionHints.looksLikeOrdinalScoring(expression.getExpression())) {
				continue;
			}
			if (isIntegerOrDecimal(site.item)) {
				continue;
			}
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_SCORING_TYPE,
					extensionPath(site.path, site.item.getExtension(), URL_CALCULATED_EXPRESSION),
					URL_CALCULATED_EXPRESSION);
		}
	}

	private void applyCircularEnableWhen() {
		Set<String> reported = new LinkedHashSet<>();
		for (String start : myEnableWhenEdges.keySet()) {
			List<String> path = new ArrayList<>();
			Set<String> onStack = new LinkedHashSet<>();
			if (findCycle(start, path, onStack, reported)) {
				// one report per cycle start discovery; findCycle adds to reported
			}
		}
	}

	/**
	 * DFS from {@code theNode}. Returns true if a cycle was reported from this walk.
	 */
	private boolean findCycle(
			String theNode, List<String> thePath, Set<String> theOnStack, Set<String> theReported) {
		if (theOnStack.contains(theNode)) {
			int idx = thePath.indexOf(theNode);
			List<String> cycle = new ArrayList<>(thePath.subList(idx, thePath.size()));
			cycle.add(theNode);
			String key = cycleKey(cycle);
			if (theReported.add(key)) {
				ItemSite site = myByLinkId.get(theNode);
				String location = site != null ? site.path + ".enableWhen[0].question" : "Questionnaire";
				addIssue(
						ResultSeverityEnum.ERROR,
						MSG_CIRCULAR_ENABLE_WHEN + formatCycle(cycle),
						location,
						theNode);
			}
			return true;
		}
		if (!myEnableWhenEdges.containsKey(theNode)) {
			return false;
		}
		thePath.add(theNode);
		theOnStack.add(theNode);
		boolean found = false;
		for (String target : myEnableWhenEdges.get(theNode)) {
			if (findCycle(target, thePath, theOnStack, theReported)) {
				found = true;
			}
		}
		theOnStack.remove(theNode);
		thePath.remove(thePath.size() - 1);
		return found;
	}

	private static String cycleKey(List<String> theCycle) {
		// Normalize so a→b→a and b→a→b share one report: rotate to lexicographically smallest start.
		if (theCycle.size() < 2) {
			return String.join(">", theCycle);
		}
		// Drop closing duplicate if present
		List<String> nodes = new ArrayList<>(theCycle);
		if (nodes.size() > 1 && nodes.get(0).equals(nodes.get(nodes.size() - 1))) {
			nodes = new ArrayList<>(nodes.subList(0, nodes.size() - 1));
		}
		if (nodes.isEmpty()) {
			return "";
		}
		int best = 0;
		for (int i = 1; i < nodes.size(); i++) {
			if (nodes.get(i).compareTo(nodes.get(best)) < 0) {
				best = i;
			}
		}
		List<String> rotated = new ArrayList<>();
		for (int i = 0; i < nodes.size(); i++) {
			rotated.add(nodes.get((best + i) % nodes.size()));
		}
		return String.join(">", rotated);
	}

	private static String formatCycle(List<String> theCycle) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < theCycle.size(); i++) {
			if (i > 0) {
				out.append(" → ");
			}
			out.append('\'').append(theCycle.get(i)).append('\'');
		}
		return out.toString();
	}

	private void applyEnableWhenToDisplay() {
		for (ItemSite site : mySites) {
			List<Questionnaire.QuestionnaireItemEnableWhenComponent> enableWhens = site.item.getEnableWhen();
			for (int i = 0; i < enableWhens.size(); i++) {
				Questionnaire.QuestionnaireItemEnableWhenComponent ew = enableWhens.get(i);
				if (!ew.hasQuestion() || ew.getQuestion().isBlank()) {
					continue;
				}
				ItemSite target = myByLinkId.get(ew.getQuestion());
				if (target == null || !target.item.hasType()) {
					continue;
				}
				if (target.item.getType() == Questionnaire.QuestionnaireItemType.DISPLAY) {
					addIssue(
							ResultSeverityEnum.ERROR,
							MSG_ENABLE_WHEN_DISPLAY,
							site.path + ".enableWhen[" + i + "].question",
							ew.getQuestion());
				}
			}
		}
	}

	private void applyImpossibleEnableWhenAll() {
		for (ItemSite site : mySites) {
			if (site.item.getEnableBehavior() != Questionnaire.EnableWhenBehavior.ALL) {
				continue;
			}
			List<Questionnaire.QuestionnaireItemEnableWhenComponent> enableWhens = site.item.getEnableWhen();
			if (enableWhens.size() < 2) {
				continue;
			}
			for (int i = 0; i < enableWhens.size(); i++) {
				Questionnaire.QuestionnaireItemEnableWhenComponent left = enableWhens.get(i);
				if (left.getOperator() != Questionnaire.QuestionnaireItemOperator.EQUAL
						|| !left.hasQuestion()
						|| left.getQuestion().isBlank()
						|| left.getAnswer() == null) {
					continue;
				}
				for (int j = i + 1; j < enableWhens.size(); j++) {
					Questionnaire.QuestionnaireItemEnableWhenComponent right = enableWhens.get(j);
					if (right.getOperator() != Questionnaire.QuestionnaireItemOperator.EQUAL
							|| !left.getQuestion().equals(right.getQuestion())
							|| right.getAnswer() == null) {
						continue;
					}
					if (contradictoryEqualAnswers(left.getAnswer(), right.getAnswer())) {
						addIssue(
								ResultSeverityEnum.ERROR,
								MSG_ENABLE_WHEN_IMPOSSIBLE,
								site.path + ".enableWhen[" + j + "]",
								left.getQuestion());
						return;
					}
				}
			}
		}
	}

	private static boolean contradictoryEqualAnswers(Type theLeft, Type theRight) {
		if (theLeft == null || theRight == null) {
			return false;
		}
		if (!theLeft.fhirType().equals(theRight.fhirType())) {
			// Different answer types against the same question cannot both hold under "=" + all.
			return true;
		}
		if (theLeft instanceof BooleanType left && theRight instanceof BooleanType right) {
			return left.hasValue() && right.hasValue() && left.booleanValue() != right.booleanValue();
		}
		if (theLeft instanceof IntegerType left && theRight instanceof IntegerType right) {
			return left.hasValue() && right.hasValue() && !left.getValue().equals(right.getValue());
		}
		if (theLeft instanceof DecimalType left && theRight instanceof DecimalType right) {
			return left.hasValue() && right.hasValue() && left.getValue().compareTo(right.getValue()) != 0;
		}
		if (theLeft instanceof StringType left && theRight instanceof StringType right) {
			String a = left.getValue();
			String b = right.getValue();
			return a != null && b != null && !a.equals(b);
		}
		return !theLeft.equalsDeep(theRight);
	}

	private void applyReadOnlyValueSource() {
		for (ItemSite site : mySites) {
			if (!site.item.getReadOnly()) {
				continue;
			}
			if (site.item.hasInitial() && !site.item.getInitial().isEmpty()) {
				continue;
			}
			if (expressionExt(site.item, URL_CALCULATED_EXPRESSION) != null) {
				continue;
			}
			if (expressionExt(site.item, URL_INITIAL_EXPRESSION) != null) {
				continue;
			}
			addIssue(ResultSeverityEnum.WARNING, MSG_READONLY_NO_SOURCE, site.path + ".readOnly", "readOnly");
		}
	}

	private static boolean isIntegerOrDecimal(Questionnaire.QuestionnaireItemComponent theItem) {
		if (!theItem.hasType()) {
			return false;
		}
		Questionnaire.QuestionnaireItemType type = theItem.getType();
		return type == Questionnaire.QuestionnaireItemType.INTEGER
				|| type == Questionnaire.QuestionnaireItemType.DECIMAL;
	}

	private static Expression expressionExt(Questionnaire.QuestionnaireItemComponent theItem, String theUrl) {
		Extension extension = firstExt(theItem.getExtension(), theUrl);
		if (extension == null) {
			return null;
		}
		Type value = extension.getValue();
		return value instanceof Expression expression ? expression : null;
	}

	private static Extension firstExt(List<Extension> theExtensions, String theUrl) {
		for (Extension extension : theExtensions) {
			if (theUrl.equals(extension.getUrl())) {
				return extension;
			}
		}
		return null;
	}

	private static String extensionPath(String theItemPath, List<Extension> theExtensions, String theUrl) {
		for (int i = 0; i < theExtensions.size(); i++) {
			if (theUrl.equals(theExtensions.get(i).getUrl())) {
				return theItemPath + ".extension[" + i + "]";
			}
		}
		return theItemPath;
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
