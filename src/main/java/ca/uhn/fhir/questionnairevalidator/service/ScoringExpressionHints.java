package ca.uhn.fhir.questionnairevalidator.service;

import java.util.regex.Pattern;

/**
 * Shared heuristics for detecting ordinal / aggregate scoring FHIRPath used by
 * {@link CodingTrendabilityValidator} and {@link MonitoringExpressionUxValidator}.
 */
final class ScoringExpressionHints {

	static final Pattern SCORING_HINT = Pattern.compile(
			"ordinal\\s*\\(|ordinalValue|itemWeight|\\.sum\\s*\\(|aggregate\\s*\\(",
			Pattern.CASE_INSENSITIVE);

	private ScoringExpressionHints() {}

	static boolean looksLikeOrdinalScoring(String theExpression) {
		if (theExpression == null || theExpression.isBlank()) {
			return false;
		}
		return SCORING_HINT.matcher(theExpression).find();
	}
}
