package ca.uhn.fhir.questionnairevalidator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "validator.terminology")
public class TerminologyProperties {

	/**
	 * Hostnames allowed for custom remote terminology URLs (SSRF guard).
	 */
	private List<String> allowedHosts = new ArrayList<>(List.of("tx.fhir.org"));

	/**
	 * Preset servers shown in the UI. Use empty url for local-only.
	 */
	private List<Preset> presets = new ArrayList<>();

	public List<String> getAllowedHosts() {
		return allowedHosts;
	}

	public void setAllowedHosts(List<String> allowedHosts) {
		this.allowedHosts = allowedHosts;
	}

	public List<Preset> getPresets() {
		return presets;
	}

	public void setPresets(List<Preset> presets) {
		this.presets = presets;
	}

	public static class Preset {
		private String id;
		private String label;
		/** Empty/null means local validation only (no remote terminology). */
		private String url = "";

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getLabel() {
			return label;
		}

		public void setLabel(String label) {
			this.label = label;
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}
	}
}
