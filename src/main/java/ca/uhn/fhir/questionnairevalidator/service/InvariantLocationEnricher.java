package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Questionnaire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processes HAPI validation issues for known StructureDefinition invariants whose
 * reported location is too coarse (often just {@code Questionnaire}).
 *
 * <p>HAPI remains the source of truth for whether the constraint failed; this class only
 * rewrites locations (and optionally fans one issue out to every offending element).
 */
final class InvariantLocationEnricher {

	private static final Pattern CONSTRAINT_KEY_PATTERN =
			Pattern.compile("Constraint failed:\\s*([A-Za-z0-9_-]+)\\s*:", Pattern.CASE_INSENSITIVE);

	private InvariantLocationEnricher() {}

	static List<ValidationIssueDto> enrich(
			IBaseResource theResource, String theJson, List<ValidationIssueDto> theIssues) {
		if (theIssues == null || theIssues.isEmpty()) {
			return theIssues == null ? List.of() : theIssues;
		}
		if (!(theResource instanceof Questionnaire questionnaire)) {
			return theIssues;
		}

		boolean needsQue2 = false;
		for (ValidationIssueDto issue : theIssues) {
			if ("que-2".equals(constraintKey(issue.getMessage()))) {
				needsQue2 = true;
				break;
			}
		}
		if (!needsQue2) {
			return theIssues;
		}

		JsonElementLocator locator = new JsonElementLocator(theJson);
		List<ValidationIssueDto> out = new ArrayList<>();
		boolean replacedQue2 = false;
		for (ValidationIssueDto issue : theIssues) {
			if ("que-2".equals(constraintKey(issue.getMessage()))) {
				if (!replacedQue2) {
					out.addAll(expandQue2(questionnaire, locator, issue));
					replacedQue2 = true;
				}
				// Drop additional coarse que-2 twins if HAPI emitted more than one.
			} else {
				out.add(issue);
			}
		}
		return out;
	}

	private static List<ValidationIssueDto> expandQue2(
			Questionnaire theQuestionnaire, JsonElementLocator theLocator, ValidationIssueDto theTemplate) {
		Map<String, List<String>> pathsByLinkId = new LinkedHashMap<>();
		collectLinkIdPaths(theQuestionnaire.getItem(), "Questionnaire", pathsByLinkId);

		List<ValidationIssueDto> expanded = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : pathsByLinkId.entrySet()) {
			List<String> paths = entry.getValue();
			if (paths.size() < 2) {
				continue;
			}
			String linkId = entry.getKey();
			for (String path : paths) {
				expanded.add(copyAtLocation(theTemplate, theLocator, path, linkId));
			}
		}

		// If we somehow cannot find duplicates on the parsed resource, keep the original issue.
		if (expanded.isEmpty()) {
			return List.of(theTemplate);
		}
		return expanded;
	}

	private static void collectLinkIdPaths(
			List<Questionnaire.QuestionnaireItemComponent> theItems,
			String thePathPrefix,
			Map<String, List<String>> theOut) {
		if (theItems == null) {
			return;
		}
		for (int i = 0; i < theItems.size(); i++) {
			Questionnaire.QuestionnaireItemComponent item = theItems.get(i);
			String itemPath = thePathPrefix + ".item[" + i + "]";
			if (item.hasLinkId()) {
				theOut.computeIfAbsent(item.getLinkId(), k -> new ArrayList<>()).add(itemPath + ".linkId");
			}
			if (item.hasItem()) {
				collectLinkIdPaths(item.getItem(), itemPath, theOut);
			}
		}
	}

	private static ValidationIssueDto copyAtLocation(
			ValidationIssueDto theTemplate,
			JsonElementLocator theLocator,
			String theLocation,
			String theLinkId) {
		ValidationIssueDto dto = new ValidationIssueDto();
		dto.setSeverity(theTemplate.getSeverity());
		String base = theTemplate.getMessage() == null ? "" : theTemplate.getMessage();
		dto.setMessage(base + " (linkId \"" + theLinkId + "\")");
		dto.setLocation(theLocation);

		JsonElementLocator.Occurrence occurrence = theLocator.locate(theLocation);
		if (occurrence != null) {
			dto.setLine(occurrence.line());
			dto.setColumn(occurrence.column());
		} else {
			dto.setLine(theTemplate.getLine());
			dto.setColumn(theTemplate.getColumn());
		}
		return dto;
	}

	static String constraintKey(String theMessage) {
		if (theMessage == null) {
			return null;
		}
		Matcher matcher = CONSTRAINT_KEY_PATTERN.matcher(theMessage);
		if (!matcher.find()) {
			return null;
		}
		return matcher.group(1).toLowerCase(Locale.ROOT);
	}
}
