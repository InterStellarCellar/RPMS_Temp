package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Locates FHIR parse errors in the raw JSON text.
 *
 * <p>
 * HAPI's parser reports parse problems only by element name (e.g.
 * {@code [element="status"]}) —
 * {@code IParseLocation} carries no line/column. This class streams the
 * document once with Jackson,
 * recording every field occurrence with its FHIR-style path
 * ({@code Questionnaire.item[0].type}),
 * line/column (of the field name), scalar value and JSON kind. Each parse error
 * is then matched to
 * an occurrence: an unused occurrence whose value equals the offending value
 * wins, then one whose
 * JSON kind matches a type mismatch, then the next unused occurrence of that
 * element. This makes
 * repeated elements (e.g. {@code status} also appearing under {@code text})
 * resolve correctly.
 *
 * <p>
 * The returned issues carry the real line/column and a path location, which the
 * frontend
 * re-resolves against the editor text to place its markers.
 */
class JsonElementLocator {

	private static final String ROOT = "\0root";

	record Occurrence(
			String elementName, List<String> path, int line, int column, String valueText, String valueKind) {
		private Occurrence withValue(String theValueText, String theValueKind) {
			return new Occurrence(elementName, path, line, column, theValueText, theValueKind);
		}
	}

	private static final class Frame {
		private final boolean myArray;
		/**
		 * Path segments from (and including) the root placeholder down to this
		 * container.
		 */
		private final List<String> myPath;
		private String myCurrentField;
		private int myNextArrayIndex;

		private Frame(boolean theArray, List<String> thePath) {
			myArray = theArray;
			myPath = thePath;
		}
	}

	private final List<Occurrence> myOccurrences = new ArrayList<>();
	private final boolean[] myUsed;
	private String myRootResourceType;

	JsonElementLocator(String theJson) {
		scan(theJson);
		myUsed = new boolean[myOccurrences.size()];
	}

	/**
	 * Find the field (or value) occurrence whose FHIR-style path equals {@code theLocation}
	 * (e.g. {@code Questionnaire.item[0].linkId}). Returns {@code null} if not found.
	 */
	Occurrence locate(String theLocation) {
		if (theLocation == null || theLocation.isBlank()) {
			return null;
		}
		for (Occurrence occurrence : myOccurrences) {
			if (theLocation.equals(formatPath(occurrence.path()))) {
				return occurrence;
			}
		}
		return null;
	}

	/**
	 * Find the first scalar occurrence whose JSON value equals {@code theValue}.
	 * Used as a fallback when a constructed FHIRPath-like location does not match.
	 */
	Occurrence locateByValueText(String theValue) {
		if (theValue == null) {
			return null;
		}
		for (Occurrence occurrence : myOccurrences) {
			if (theValue.equals(occurrence.valueText())) {
				return occurrence;
			}
		}
		return null;
	}

	List<ValidationIssueDto> toIssues(List<CollectingParserErrorHandler.ParseError> theErrors) {
		List<ValidationIssueDto> out = new ArrayList<>();
		for (CollectingParserErrorHandler.ParseError error : theErrors) {
			out.add(toIssue(error));
		}
		return out;
	}

	ValidationIssueDto toIssue(CollectingParserErrorHandler.ParseError theError) {
		ValidationIssueDto dto = new ValidationIssueDto();
		dto.setSeverity(ResultSeverityEnum.ERROR.getCode());
		dto.setMessage(theError.message());
		Occurrence occurrence = resolve(theError);
		if (occurrence == null) {
			dto.setLine(1);
			dto.setColumn(1);
			dto.setLocation("FHIR parse");
			return dto;
		}
		dto.setLine(occurrence.line());
		dto.setColumn(occurrence.column());
		dto.setLocation(formatPath(occurrence.path()));
		return dto;
	}

	private Occurrence resolve(CollectingParserErrorHandler.ParseError theError) {
		Occurrence occurrence = find(theError.elementName(), theError);
		// For choice-type repeats (e.g. HAPI reports element "answer" or "value" but the
		// JSON keys are "answerString", "answerInteger", "valueString", etc.), try a prefix
		// match to locate the second choice key that caused the repeat error.
		if (occurrence == null && theError.elementName() != null
				&& theError.message() != null && theError.message().contains("HAPI-1823")) {
			occurrence = findByPrefix(theError.elementName());
		}
		if (occurrence == null && theError.parentElementName() != null) {
			// e.g. a missing required element: point at the parent element instead.
			occurrence = find(
					theError.parentElementName(),
					new CollectingParserErrorHandler.ParseError(null, theError.parentElementName(), null, null, null));
		}
		return occurrence;
	}

	/**
	 * For virtual FHIR choice elements like "answer" or "value", find the second occurrence
	 * of a key that starts with that prefix (e.g. "answerInteger" when "answerString" was
	 * already seen). This locates the duplicate that triggered HAPI-1823.
	 */
	private Occurrence findByPrefix(String thePrefix) {
		Occurrence first = null;
		for (int i = 0; i < myOccurrences.size(); i++) {
			String name = myOccurrences.get(i).elementName();
			if (name != null && name.startsWith(thePrefix) && name.length() > thePrefix.length()
					&& Character.isUpperCase(name.charAt(thePrefix.length()))) {
				if (first == null) {
					first = myOccurrences.get(i);
				} else {
					myUsed[i] = true;
					return myOccurrences.get(i);
				}
			}
		}
		// If only one choice key found, point at it anyway (the duplicate was consumed by HAPI).
		return first;
	}

	private Occurrence find(String theElementName, CollectingParserErrorHandler.ParseError theError) {
		if (theElementName == null || theElementName.isBlank()) {
			return null;
		}
		List<Integer> candidates = new ArrayList<>();
		for (int i = 0; i < myOccurrences.size(); i++) {
			String name = myOccurrences.get(i).elementName();
			if (name.equals(theElementName) || name.equals("_" + theElementName)) {
				candidates.add(i);
			}
		}
		if (candidates.isEmpty()) {
			return null;
		}
		// 1) unused occurrence carrying exactly the offending value
		if (theError.offendingValue() != null) {
			for (int i : candidates) {
				if (!myUsed[i] && theError.offendingValue().equals(myOccurrences.get(i).valueText())) {
					return claim(i);
				}
			}
		}
		// 2) unused occurrence with the JSON kind reported for a type mismatch
		if (theError.foundValueKind() != null) {
			for (int i : candidates) {
				if (!myUsed[i] && theError.foundValueKind().equalsIgnoreCase(myOccurrences.get(i).valueKind())) {
					return claim(i);
				}
			}
		}
		// 3) next unused occurrence, else the first one
		for (int i : candidates) {
			if (!myUsed[i]) {
				return claim(i);
			}
		}
		return myOccurrences.get(candidates.get(0));
	}

	private Occurrence claim(int theIndex) {
		myUsed[theIndex] = true;
		return myOccurrences.get(theIndex);
	}

	private String formatPath(List<String> thePath) {
		String root = myRootResourceType == null || myRootResourceType.isBlank() ? "Questionnaire" : myRootResourceType;
		StringBuilder out = new StringBuilder();
		for (String segment : thePath) {
			if (ROOT.equals(segment)) {
				out.append(root);
			} else if (segment.startsWith("[")) {
				out.append(segment);
			} else {
				if (out.length() > 0) {
					out.append('.');
				}
				out.append(segment);
			}
		}
		return out.toString();
	}

	private void scan(String theJson) {
		JsonFactory factory = new JsonFactory();
		Deque<Frame> stack = new ArrayDeque<>();
		try (JsonParser parser = factory.createParser(theJson)) {
			Occurrence pendingValue = null;
			int pendingIndex = -1;
			JsonToken token;
			while ((token = parser.nextToken()) != null) {
				switch (token) {
					case FIELD_NAME -> {
						Frame frame = stack.peek();
						String name = parser.getText();
						if (frame != null) {
							frame.myCurrentField = name;
						}
						List<String> path = new ArrayList<>(frame == null ? List.of(ROOT) : frame.myPath);
						path.add(name);
						JsonLocation loc = parser.getTokenLocation();
						pendingIndex = myOccurrences.size();
						pendingValue = new Occurrence(name, path, loc.getLineNr(), loc.getColumnNr(), null, null);
						myOccurrences.add(pendingValue);
					}
					case START_OBJECT, START_ARRAY -> {
						boolean array = token == JsonToken.START_ARRAY;
						if (pendingValue != null) {
							myOccurrences.set(pendingIndex, pendingValue.withValue(null, array ? "ARRAY" : "OBJECT"));
							pendingValue = null;
							pendingIndex = -1;
						}
						List<String> path = childPath(stack);
						stack.push(new Frame(array, path));
					}
					case END_OBJECT, END_ARRAY -> {
						if (!stack.isEmpty()) {
							stack.pop();
						}
					}
					case VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT, VALUE_TRUE, VALUE_FALSE, VALUE_NULL -> {
						String kind = switch (token) {
							case VALUE_STRING -> "STRING";
							case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> "NUMBER";
							case VALUE_TRUE, VALUE_FALSE -> "BOOLEAN";
							default -> "NULL";
						};
						if (pendingValue != null) {
							Occurrence filled = pendingValue.withValue(parser.getText(), kind);
							myOccurrences.set(pendingIndex, filled);
							if (filled.path().size() == 2 && "resourceType".equals(filled.elementName())) {
								myRootResourceType = filled.valueText();
							}
							pendingValue = null;
							pendingIndex = -1;
						} else {
							// Scalar array element: consume an array index slot.
							consumeArraySlot(stack);
						}
					}
					default -> {
						// no-op
					}
				}
			}
		} catch (IOException e) {
			// JSON well-formedness is checked before this runs; on failure we simply locate
			// nothing.
			myOccurrences.clear();
		}
	}

	/**
	 * Path for a container token that is about to be pushed, based on the current
	 * container.
	 */
	private static List<String> childPath(Deque<Frame> theStack) {
		Frame parent = theStack.peek();
		if (parent == null) {
			return List.of(ROOT);
		}
		List<String> path = new ArrayList<>(parent.myPath);
		if (parent.myArray) {
			path.add("[" + (parent.myNextArrayIndex++) + "]");
		} else if (parent.myCurrentField != null) {
			// The field segment itself was already added to occurrence paths; for the
			// container
			// value, the field name is the last segment of its own path.
			if (path.isEmpty() || !parent.myCurrentField.equals(path.get(path.size() - 1))) {
				path.add(parent.myCurrentField);
			}
		}
		return path;
	}

	private static void consumeArraySlot(Deque<Frame> theStack) {
		Frame top = theStack.peek();
		if (top != null && top.myArray) {
			top.myNextArrayIndex++;
		}
	}
}
