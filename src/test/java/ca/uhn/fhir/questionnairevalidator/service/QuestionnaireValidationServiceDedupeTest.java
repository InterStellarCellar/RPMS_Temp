package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuestionnaireValidationServiceDedupeTest {

	@Test
	void dedupeIssues_keepsRemoteWrapperOverCoreForUnknownCode() {
		ValidationIssueDto core = issue(8, 7, "error", "Unknown code '0000-0' in the CodeSystem 'http://loinc.org' version '2.82'");
		ValidationIssueDto remote = issue(
				8,
				7,
				"error",
				"Unknown code \"http://loinc.org#0000-0\". The Remote Terminology server https://tx.fhir.org/r4 returned Unknown code '0000-0' in the CodeSystem 'http://loinc.org' version '2.82'");
		ValidationIssueDto other = issue(18, 11, "error", "Unknown code '99999-9' in the CodeSystem 'http://loinc.org' version '2.82'");
		ValidationIssueDto remoteOther = issue(
				18,
				11,
				"error",
				"Unknown code \"http://loinc.org#99999-9\". The Remote Terminology server https://tx.fhir.org/r4 returned Unknown code '99999-9'");

		List<ValidationIssueDto> deduped =
				QuestionnaireValidationService.dedupeIssues(List.of(core, remote, other, remoteOther));

		assertEquals(2, deduped.size());
		assertEquals(remote.getMessage(), deduped.get(0).getMessage());
		assertEquals(remoteOther.getMessage(), deduped.get(1).getMessage());
	}

	@Test
	void dedupeIssues_keepsRemoteWrapperOverCoreForWrongDisplay() {
		ValidationIssueDto core = issue(
				9,
				5,
				"error",
				"Wrong Display Name 'Vital signs, weight, height, head circumference' for http://loinc.org#85353-1. Valid display is one of 5 choices: 'Vital signs, weight, height, head circumference, oxygen saturation and BMI panel' (en-US)");
		ValidationIssueDto remote = issue(
				9,
				5,
				"error",
				"Unknown code \"http://loinc.org#85353-1\". The Remote Terminology server https://tx.fhir.org/r4 returned Wrong Display Name 'Vital signs, weight, height, head circumference' for http://loinc.org#85353-1.");

		List<ValidationIssueDto> deduped = QuestionnaireValidationService.dedupeIssues(List.of(core, remote));

		assertEquals(1, deduped.size());
		assertEquals(remote.getMessage(), deduped.get(0).getMessage());
	}

	@Test
	void dedupeIssues_keepsCoreOnlyWhenNoRemoteTwin() {
		ValidationIssueDto structural = issue(1, 1, "warning", "Constraint failed: dom-6: 'A resource should have narrative'");
		ValidationIssueDto localTerm = issue(5, 3, "error", "Unknown code 'notARealUcumCode' in the CodeSystem 'http://unitsofmeasure.org'");

		List<ValidationIssueDto> deduped =
				QuestionnaireValidationService.dedupeIssues(List.of(structural, localTerm));

		assertEquals(2, deduped.size());
		assertEquals(structural.getMessage(), deduped.get(0).getMessage());
		assertEquals(localTerm.getMessage(), deduped.get(1).getMessage());
	}

	private static ValidationIssueDto issue(int line, int col, String severity, String message) {
		ValidationIssueDto dto = new ValidationIssueDto();
		dto.setLine(line);
		dto.setColumn(col);
		dto.setSeverity(severity);
		dto.setMessage(message);
		return dto;
	}
}
