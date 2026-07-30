package ca.uhn.fhir.questionnairevalidator.config;

import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationBeansConfigPackageFilterTest {

	@Test
	void skipsItemTypeCodeSystemFromAnyPackage() {
		CodeSystem cs = new CodeSystem();
		cs.setUrl("http://hl7.org/fhir/item-type");
		cs.setVersion("5.0.0");
		assertTrue(ValidationBeansConfig.shouldSkipPackageResource(cs, "hl7.fhir.uv.extensions.r4-5.3.0.tgz"));
		assertTrue(ValidationBeansConfig.shouldSkipPackageResource(cs, "hl7.fhir.uv.xver-r5.r4-0.1.0.tgz"));
	}

	@Test
	void skipsCoreCodeSystemsFromXverOnly() {
		CodeSystem administrativeGender = new CodeSystem();
		administrativeGender.setUrl("http://hl7.org/fhir/administrative-gender");
		administrativeGender.setVersion("5.0.0");

		assertTrue(ValidationBeansConfig.shouldSkipPackageResource(
				administrativeGender, "hl7.fhir.uv.xver-r5.r4-0.1.0.tgz"));
		assertFalse(ValidationBeansConfig.shouldSkipPackageResource(
				administrativeGender, "hl7.fhir.uv.extensions.r4-5.3.0.tgz"));
	}

	@Test
	void keepsExtensionAndIgCodeSystems() {
		CodeSystem versionAlgorithm = new CodeSystem();
		versionAlgorithm.setUrl("http://hl7.org/fhir/version-algorithm");
		assertFalse(ValidationBeansConfig.shouldSkipPackageResource(
				versionAlgorithm, "hl7.fhir.uv.extensions.r4-5.3.0.tgz"));

		CodeSystem tho = new CodeSystem();
		tho.setUrl("http://terminology.hl7.org/CodeSystem/v2-0001");
		assertFalse(ValidationBeansConfig.shouldSkipPackageResource(tho, "hl7.terminology.r4-7.2.0.tgz"));
	}

	@Test
	void keepsStructureDefinitionsFromXver() {
		StructureDefinition sd = new StructureDefinition();
		sd.setUrl("http://hl7.org/fhir/5.0/StructureDefinition/extension-Questionnaire.item.disabledDisplay");
		assertFalse(ValidationBeansConfig.shouldSkipPackageResource(sd, "hl7.fhir.uv.xver-r5.r4-0.1.0.tgz"));
	}
}
