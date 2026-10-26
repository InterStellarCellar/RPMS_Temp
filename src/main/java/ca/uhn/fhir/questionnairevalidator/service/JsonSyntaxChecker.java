package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.ResultSeverityEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * Error-recovering JSON (RFC 8259) syntax checker.
 *
 * <p>Neither Jackson's streaming parser nor the browser's {@code JSON.parse} can report more than
 * the first syntax error — both abort there. This hand-written recursive-descent checker instead
 * records an error, resynchronises at the next plausible token (next key, comma, closing
 * bracket, ...), and keeps going, so one pass reports every independent syntax problem in the
 * document, each with its real line/column.
 *
 * <p>It is deliberately syntax-only: it checks tokenisation and document structure (strings and
 * escapes, numbers, literals, nesting, commas/colons/brackets, trailing content). It accepts every
 * well-formed JSON document; semantic problems are left to the FHIR parser/validator downstream.
 */
class JsonSyntaxChecker {

	private static final int MAX_ERRORS = 50;
	private static final int MAX_DEPTH = 256;

	/** Control-flow signal: too many errors / too deep to keep recovering usefully. */
	private static final class AbortParse extends RuntimeException {}

	private final String myText;
	private final int myLength;
	private final List<Integer> myLineStarts = new ArrayList<>();
	private final List<ValidationIssueDto> myErrors = new ArrayList<>();
	private int myPos;

	private JsonSyntaxChecker(String theText) {
		myText = theText == null ? "" : theText;
		myLength = myText.length();
		myLineStarts.add(0);
		for (int i = 0; i < myLength; i++) {
			if (myText.charAt(i) == '\n') {
				myLineStarts.add(i + 1);
			}
		}
	}

	static List<ValidationIssueDto> check(String theJson) {
		JsonSyntaxChecker checker = new JsonSyntaxChecker(theJson);
		try {
			checker.parseDocument();
		} catch (AbortParse e) {
			// Already recorded why we stopped.
		}
		return checker.myErrors;
	}

	// ---------------------------------------------------------- document / values

	private void parseDocument() {
		skipWhitespace();
		if (eof()) {
			addError(myPos, "Unexpected end of input: expected a JSON value");
			return;
		}
		parseValue(0);
		skipWhitespace();
		while (!eof()) {
			addError(myPos, "Unexpected content after the top-level JSON value");
			if (peek() == '"') {
				parseString();
			} else {
				while (!eof() && !isWhitespace(peek())) {
					myPos++;
				}
			}
			skipWhitespace();
		}
	}

	private void parseValue(int theDepth) {
		if (theDepth > MAX_DEPTH) {
			addError(myPos, "JSON is nested too deeply (more than " + MAX_DEPTH + " levels)");
			throw new AbortParse();
		}
		skipWhitespace();
		if (eof()) {
			addError(myPos, "Unexpected end of input: expected a JSON value");
			return;
		}
		char c = peek();
		switch (c) {
			case '{' -> parseObject(theDepth);
			case '[' -> parseArray(theDepth);
			case '"' -> parseString();
			case 't' -> parseLiteral("true");
			case 'f' -> parseLiteral("false");
			case 'n' -> parseLiteral("null");
			case '}', ']', ',', ':' -> addError(myPos, "Expected a JSON value");
			default -> {
				if (c == '-' || isDigit(c)) {
					parseNumber();
				} else {
					addError(myPos, "Unexpected character '" + printable(c) + "': expected a JSON value");
					skipUnexpectedToken();
				}
			}
		}
	}

	private void parseObject(int theDepth) {
		myPos++; // '{'
		skipWhitespace();
		if (!eof() && peek() == '}') {
			myPos++;
			return;
		}
		boolean afterComma = false;
		while (true) {
			skipWhitespace();
			if (eof()) {
				addError(myPos, "Unterminated object: expected a string key or '}'");
				return;
			}
			if (peek() == '}') {
				if (afterComma) {
					addError(myPos, "Trailing comma in object");
				}
				myPos++;
				return;
			}
			if (peek() == '"') {
				parseString();
			} else {
				addError(myPos, "Expected a string key in object");
				if (!recoverFromBadKey()) {
					return;
				}
				if (!eof() && peek() == '}') {
					continue;
				}
			}
			afterComma = false;

			skipWhitespace();
			if (!eof() && peek() == ':') {
				myPos++;
			} else {
				addError(myPos, "Expected ':' after object key");
			}
			int errorsBeforeValue = myErrors.size();
			parseValue(theDepth + 1);

			skipWhitespace();
			if (eof()) {
				// If the value parse already reported the EOF, don't cascade a second message.
				if (myErrors.size() == errorsBeforeValue) {
					addError(myPos, "Unterminated object: expected ',' or '}'");
				}
				return;
			}
			if (peek() == ',') {
				myPos++;
				afterComma = true;
				continue;
			}
			if (peek() == '}') {
				myPos++;
				return;
			}
			// Missing comma: report, then let the loop read the next key — this is what lets
			// later, independent errors still be found. Wrong closer ']' is left for the parent
			// array. Any other stuck token must be consumed or we spin forever.
			addError(myPos, "Expected ',' or '}' in object");
			if (peek() == ']') {
				return;
			}
			if (!eof() && peek() != '"') {
				skipUnexpectedToken();
			}
		}
	}

	private void parseArray(int theDepth) {
		myPos++; // '['
		skipWhitespace();
		if (!eof() && peek() == ']') {
			myPos++;
			return;
		}
		boolean afterComma = false;
		while (true) {
			skipWhitespace();
			if (eof()) {
				addError(myPos, "Unterminated array: expected a value or ']'");
				return;
			}
			if (peek() == ']') {
				if (afterComma) {
					addError(myPos, "Trailing comma in array");
				}
				myPos++;
				return;
			}
			// Wrong closer: outer object ended while this array is still open. Leave '}' for
			// the parent — do not call parseValue (it would not advance and we would spin).
			if (peek() == '}') {
				addError(myPos, "Expected ',' or ']' in array");
				return;
			}
			int errorsBeforeValue = myErrors.size();
			parseValue(theDepth + 1);
			afterComma = false;

			skipWhitespace();
			if (eof()) {
				if (myErrors.size() == errorsBeforeValue) {
					addError(myPos, "Unterminated array: expected ',' or ']'");
				}
				return;
			}
			if (peek() == ',') {
				myPos++;
				afterComma = true;
				continue;
			}
			if (peek() == ']') {
				myPos++;
				return;
			}
			addError(myPos, "Expected ',' or ']' in array");
			if (peek() == '}') {
				return;
			}
			skipUnexpectedToken();
		}
	}

	// ---------------------------------------------------------- tokens

	private void parseString() {
		int stringStart = myPos;
		myPos++; // opening quote
		while (!eof()) {
			char c = peek();
			if (c == '"') {
				myPos++;
				return;
			}
			if (c == '\\') {
				parseEscape(stringStart);
				if (eof()) {
					return;
				}
				continue;
			}
			if (c < 0x20) {
				if (c == '\n' || c == '\r') {
					// Treat the string as closed here so the surrounding structure can recover.
					addError(stringStart, "Unterminated string");
					return;
				}
				addError(myPos, "Unescaped control character in string");
			}
			myPos++;
		}
		addError(stringStart, "Unterminated string");
	}

	private void parseEscape(int theStringStart) {
		int slash = myPos;
		myPos++; // backslash
		if (eof()) {
			addError(theStringStart, "Unterminated string");
			return;
		}
		char esc = peek();
		switch (esc) {
			case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> myPos++;
			case 'u' -> {
				myPos = slash + 2; // past the escape introducer
				int digits = 0;
				while (digits < 4 && !eof() && isHex(peek())) {
					myPos++;
					digits++;
				}
				if (digits != 4) {
					addError(slash, "Invalid \\u escape in string: expected 4 hexadecimal digits");
				}
			}
			default -> {
				addError(slash, "Invalid escape character '\\" + printable(esc) + "' in string");
				myPos++;
			}
		}
	}

	private void parseLiteral(String theExpected) {
		int start = myPos;
		while (!eof() && Character.isLetter(peek())) {
			myPos++;
		}
		String actual = myText.substring(start, myPos);
		if (!theExpected.equals(actual)) {
			addError(start, "Invalid literal '" + actual + "': expected '" + theExpected + "'");
		}
	}

	private void parseNumber() {
		int start = myPos;
		boolean errorRecorded = false;
		if (!eof() && peek() == '-') {
			myPos++;
		}
		if (eof() || !isDigit(peek())) {
			addError(start, "Invalid number");
			return;
		}
		if (peek() == '0') {
			myPos++;
			if (!eof() && isDigit(peek())) {
				addError(start, "Invalid number: leading zeros are not allowed");
				errorRecorded = true;
				while (!eof() && isDigit(peek())) {
					myPos++;
				}
			}
		} else {
			while (!eof() && isDigit(peek())) {
				myPos++;
			}
		}
		if (!eof() && peek() == '.') {
			myPos++;
			if (eof() || !isDigit(peek())) {
				if (!errorRecorded) {
					addError(start, "Invalid number: expected a digit after the decimal point");
					errorRecorded = true;
				}
			}
			while (!eof() && isDigit(peek())) {
				myPos++;
			}
		}
		if (!eof() && (peek() == 'e' || peek() == 'E')) {
			myPos++;
			if (!eof() && (peek() == '+' || peek() == '-')) {
				myPos++;
			}
			if (eof() || !isDigit(peek())) {
				if (!errorRecorded) {
					addError(start, "Invalid number: expected a digit in the exponent");
					errorRecorded = true;
				}
			}
			while (!eof() && isDigit(peek())) {
				myPos++;
			}
		}
		// Stuck-on suffix such as 12abc or 1.2.3: one error, then consume it to resynchronise.
		if (!eof() && (Character.isLetter(peek()) || peek() == '.' || peek() == '+' || peek() == '-')) {
			if (!errorRecorded) {
				addError(start, "Invalid number");
			}
			while (!eof() && (Character.isLetterOrDigit(peek()) || peek() == '.' || peek() == '+' || peek() == '-')) {
				myPos++;
			}
		}
	}

	// ---------------------------------------------------------- recovery

	/**
	 * After a non-string token where an object key was expected: consume the offending token and,
	 * if it was followed by ':', the value too. Returns {@code false} if the enclosing object
	 * should be abandoned (EOF / closing brace will be handled by the caller loop).
	 */
	private boolean recoverFromBadKey() {
		if (!eof() && peek() == ',') {
			myPos++;
			return true;
		}
		skipUnexpectedToken();
		skipWhitespace();
		if (!eof() && peek() == ':') {
			myPos++;
			parseValue(0);
			skipWhitespace();
			if (!eof() && peek() == ',') {
				myPos++;
			}
		}
		return !eof();
	}

	private void skipUnexpectedToken() {
		if (eof()) {
			return;
		}
		if (peek() == '"') {
			parseString();
			return;
		}
		int before = myPos;
		while (!eof() && !isWhitespace(peek()) && peek() != ',' && peek() != '}' && peek() != ']' && peek() != ':') {
			myPos++;
		}
		if (myPos == before) {
			myPos++; // guarantee progress on a structural character
		}
	}

	// ---------------------------------------------------------- helpers

	private void addError(int theOffset, String theMessage) {
		int offset = Math.max(0, Math.min(theOffset, myLength));
		// Collapse exact duplicates produced by recovery at the same spot.
		for (ValidationIssueDto existing : myErrors) {
			if (existing.getMessage().equals(theMessage)
					&& existing.getLine() == lineOf(offset)
					&& existing.getColumn() == columnOf(offset)) {
				return;
			}
		}
		ValidationIssueDto dto = new ValidationIssueDto();
		dto.setSeverity(ResultSeverityEnum.FATAL.getCode());
		dto.setMessage(theMessage);
		dto.setLine(lineOf(offset));
		dto.setColumn(columnOf(offset));
		dto.setLocation("JSON");
		myErrors.add(dto);
		if (myErrors.size() >= MAX_ERRORS) {
			throw new AbortParse();
		}
	}

	private int lineOf(int theOffset) {
		return lineIndexOf(theOffset) + 1;
	}

	private int columnOf(int theOffset) {
		return theOffset - myLineStarts.get(lineIndexOf(theOffset)) + 1;
	}

	/** 0-based index of the line containing an offset, via the precomputed line-start table. */
	private int lineIndexOf(int theOffset) {
		int index = java.util.Collections.binarySearch(myLineStarts, theOffset);
		return index >= 0 ? index : -index - 2;
	}

	private void skipWhitespace() {
		while (!eof() && isWhitespace(peek())) {
			myPos++;
		}
	}

	private boolean eof() {
		return myPos >= myLength;
	}

	private char peek() {
		return myText.charAt(myPos);
	}

	private static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r';
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isHex(char c) {
		return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	private static String printable(char c) {
		return c < 0x20 ? String.format("\\u%04x", (int) c) : Character.toString(c);
	}
}
