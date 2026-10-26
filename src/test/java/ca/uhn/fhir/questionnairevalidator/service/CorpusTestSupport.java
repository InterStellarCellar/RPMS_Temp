package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.questionnairevalidator.config.ValidationBeansConfig;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;

/**
 * Shared production validation chain for corpus tests. Reloading NPM packages
 * per test class exhausts the surefire heap; one chain is enough and identical.
 */
final class CorpusTestSupport {

	private static final ValidationBeansConfig CONFIG = new ValidationBeansConfig();
	private static final FhirContext FHIR_CONTEXT = CONFIG.fhirContextR4();
	private static final ValidationSupportChain CHAIN = CONFIG.localValidationSupportChain(FHIR_CONTEXT);
	private static final QuestionnaireValidationService SERVICE =
			new QuestionnaireValidationService(FHIR_CONTEXT, CHAIN);

	private CorpusTestSupport() {}

	static FhirContext fhirContext() {
		return FHIR_CONTEXT;
	}

	static ValidationSupportChain chain() {
		return CHAIN;
	}

	static QuestionnaireValidationService service() {
		return SERVICE;
	}
}
