package ca.uhn.fhir.questionnairevalidator.dto;

import java.util.ArrayList;
import java.util.List;

public class ValidateResponse {

	private boolean ok;
	private List<ValidationIssueDto> issues = new ArrayList<>();

	public boolean isOk() {
		return ok;
	}

	public void setOk(boolean ok) {
		this.ok = ok;
	}

	public List<ValidationIssueDto> getIssues() {
		return issues;
	}

	public void setIssues(List<ValidationIssueDto> issues) {
		this.issues = issues;
	}
}
