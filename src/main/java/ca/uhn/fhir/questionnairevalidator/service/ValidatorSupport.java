package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import org.hl7.fhir.r4.model.Extension;

import java.util.List;

/**
 * Helpers shared by the authoring-time validators: extension lookup by URL, FHIRPath-style
 * extension paths, and issue construction with line/column resolution through
 * {@link JsonElementLocator}.
 */
final class ValidatorSupport {

	private ValidatorSupport() {}

	/** First extension with the given URL, or {@code null}. */
	static Extension firstExt(List<Extension> theExtensions, String theUrl) {
		for (Extension extension : theExtensions) {
			if (theUrl.equals(extension.getUrl())) {
				return extension;
			}
		}
		return null;
	}

	/** Index of the first extension with the given URL, or {@code -1}. */
	static int extensionIndex(List<Extension> theExtensions, String theUrl) {
		for (int i = 0; i < theExtensions.size(); i++) {
			if (theUrl.equals(theExtensions.get(i).getUrl())) {
				return i;
			}
		}
		return -1;
	}

	/** {@code <parent>.extension[i]} for the first extension with the URL, else the parent path. */
	static String extensionPath(String theParentPath, List<Extension> theExtensions, String theUrl) {
		int index = extensionIndex(theExtensions, theUrl);
		return index >= 0 ? theParentPath + ".extension[" + index + "]" : theParentPath;
	}

	/**
	 * Build an issue located at {@code thePath}; falls back to the first occurrence of
	 * {@code theValue} in the source text, then to line 1 column 1.
	 */
	static ValidationIssueDto newIssue(
			JsonElementLocator theLocator,
			ResultSeverityEnum theSeverity,
			String theMessage,
			String thePath,
			String theValue) {
		ValidationIssueDto dto = new ValidationIssueDto();
		dto.setSeverity(theSeverity.getCode());
		dto.setMessage(theMessage);
		JsonElementLocator.Occurrence occurrence = theLocator.locate(thePath);
		if (occurrence == null && theValue != null) {
			occurrence = theLocator.locateByValueText(theValue);
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
		return dto;
	}
}
