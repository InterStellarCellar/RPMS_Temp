package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParserErrorHandler;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.parser.json.BaseJsonLikeValue.ScalarType;
import ca.uhn.fhir.parser.json.BaseJsonLikeValue.ValueType;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser error handler that records every parse problem instead of throwing on the first one
 * (as {@link StrictErrorHandler} does) or only logging it (as {@code LenientErrorHandler} does).
 *
 * <p>Messages are produced by delegating to {@link StrictErrorHandler} and capturing the
 * {@link DataFormatException} it throws, so wording/HAPI codes are identical to the strict
 * behaviour this application previously exposed for the first error. Nothing here ever throws,
 * so HAPI keeps parsing and subsequent errors are collected too.
 */
class CollectingParserErrorHandler implements IParserErrorHandler {

	/**
	 * One recorded parse problem.
	 *
	 * @param message         the strict-handler message (e.g. {@code HAPI-1821: [element="status"] ...})
	 * @param elementName     the JSON element the problem is about, if known (e.g. {@code status})
	 * @param parentElementName fallback element to point at when {@code elementName} itself does not
	 *                        occur in the document (e.g. a missing required element: point at its parent)
	 * @param offendingValue  the invalid value, if known; used to disambiguate repeated elements
	 * @param foundValueKind  JSON kind found for a type mismatch (e.g. {@code OBJECT}), if known
	 */
	record ParseError(
			String message,
			String elementName,
			String parentElementName,
			String offendingValue,
			String foundValueKind) {}

	private final StrictErrorHandler myStrict = new StrictErrorHandler();
	private final List<ParseError> myErrors = new ArrayList<>();

	List<ParseError> getErrors() {
		return myErrors;
	}

	private void capture(Runnable theStrictCall, ParseError theError) {
		try {
			theStrictCall.run();
			// Strict handler did not throw (unexpected): still record what we know.
			myErrors.add(theError);
		} catch (DataFormatException e) {
			myErrors.add(new ParseError(
					e.getMessage(),
					theError.elementName(),
					theError.parentElementName(),
					theError.offendingValue(),
					theError.foundValueKind()));
		}
	}

	private static String parentOf(IParseLocation theLocation) {
		return theLocation == null ? null : theLocation.getParentElementName();
	}

	@Override
	public void containedResourceWithNoId(IParseLocation theLocation) {
		capture(
				() -> myStrict.containedResourceWithNoId(theLocation),
				new ParseError(null, "contained", parentOf(theLocation), null, null));
	}

	@Override
	public void incorrectJsonType(
			IParseLocation theLocation,
			String theElementName,
			ValueType theExpectedValueType,
			ScalarType theExpectedScalarType,
			ValueType theFoundValueType,
			ScalarType theFoundScalarType) {
		String foundKind = theFoundValueType == null ? null : theFoundValueType.name();
		capture(
				() -> myStrict.incorrectJsonType(
						theLocation,
						theElementName,
						theExpectedValueType,
						theExpectedScalarType,
						theFoundValueType,
						theFoundScalarType),
				new ParseError(null, theElementName, parentOf(theLocation), null, foundKind));
	}

	@Override
	public void invalidValue(IParseLocation theLocation, String theValue, String theError) {
		// For invalidValue, IParseLocation carries the element name itself (see ParserState).
		capture(
				() -> myStrict.invalidValue(theLocation, theValue, theError),
				new ParseError(null, parentOf(theLocation), null, theValue, null));
	}

	@Override
	public void missingRequiredElement(IParseLocation theLocation, String theElementName) {
		capture(
				() -> myStrict.missingRequiredElement(theLocation, theElementName),
				new ParseError(null, theElementName, parentOf(theLocation), null, null));
	}

	@Override
	public void unexpectedRepeatingElement(IParseLocation theLocation, String theElementName) {
		capture(
				() -> myStrict.unexpectedRepeatingElement(theLocation, theElementName),
				new ParseError(null, theElementName, parentOf(theLocation), null, null));
	}

	@Override
	public void unknownAttribute(IParseLocation theLocation, String theAttributeName) {
		capture(
				() -> myStrict.unknownAttribute(theLocation, theAttributeName),
				new ParseError(null, theAttributeName, parentOf(theLocation), null, null));
	}

	@Override
	public void unknownElement(IParseLocation theLocation, String theElementName) {
		capture(
				() -> myStrict.unknownElement(theLocation, theElementName),
				new ParseError(null, theElementName, parentOf(theLocation), null, null));
	}

	@Override
	public void unknownReference(IParseLocation theLocation, String theReference) {
		capture(
				() -> myStrict.unknownReference(theLocation, theReference),
				new ParseError(null, null, parentOf(theLocation), theReference, null));
	}

	@Override
	public void invalidInternalReference(IParseLocation theLocation, String theReference) {
		capture(
				() -> myStrict.invalidInternalReference(theLocation, theReference),
				new ParseError(null, null, parentOf(theLocation), theReference, null));
	}

	@Override
	public void extensionContainsValueAndNestedExtensions(IParseLocation theLocation) {
		capture(
				() -> myStrict.extensionContainsValueAndNestedExtensions(theLocation),
				new ParseError(null, null, parentOf(theLocation), null, null));
	}
}
