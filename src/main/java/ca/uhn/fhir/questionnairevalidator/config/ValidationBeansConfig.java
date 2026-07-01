package ca.uhn.fhir.questionnairevalidator.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ValidationBeansConfig {

	@Bean
	public FhirContext fhirContextR4() {
		return FhirContext.forR4();
	}

	@Bean
	public ValidationSupportChain validationSupportChain(FhirContext theFhirContext) {
		return new ValidationSupportChain(
				new DefaultProfileValidationSupport(theFhirContext),
				new InMemoryTerminologyServerValidationSupport(theFhirContext));
	}
}
