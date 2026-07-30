package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.config.TerminologyProperties;
import ca.uhn.fhir.questionnairevalidator.dto.TerminologyServerDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TerminologyServerService {

	private final TerminologyProperties myProperties;

	public TerminologyServerService(TerminologyProperties theProperties) {
		myProperties = theProperties;
	}

	public List<TerminologyServerDto> listPresets() {
		List<TerminologyServerDto> out = new ArrayList<>();
		for (TerminologyProperties.Preset preset : myProperties.getPresets()) {
			out.add(new TerminologyServerDto(
					preset.getId(),
					preset.getLabel(),
					preset.getUrl() == null ? "" : preset.getUrl()));
		}
		return out;
	}

	/**
	 * Resolve the remote terminology base URL to use for a validate request.
	 * Empty string means local-only (no remote).
	 *
	 * @param thePresetId optional preset id from UI
	 * @param theCustomUrl optional custom URL (must be https and on allowlist)
	 */
	public String resolveRemoteUrl(String thePresetId, String theCustomUrl) {
		if (theCustomUrl != null && !theCustomUrl.isBlank()) {
			return validateAndNormalizeCustomUrl(theCustomUrl.trim());
		}
		if (thePresetId == null || thePresetId.isBlank() || "none".equalsIgnoreCase(thePresetId)) {
			return "";
		}
		Optional<TerminologyProperties.Preset> match = myProperties.getPresets().stream()
				.filter(p -> thePresetId.equals(p.getId()))
				.findFirst();
		if (match.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown terminology server preset: " + thePresetId);
		}
		String url = match.get().getUrl();
		return url == null ? "" : url.trim();
	}

	private String validateAndNormalizeCustomUrl(String theUrl) {
		final URI uri;
		try {
			uri = URI.create(theUrl);
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid terminology server URL");
		}
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Terminology server URL must use https");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Terminology server URL must include a host");
		}
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		boolean allowed = myProperties.getAllowedHosts().stream()
				.map(h -> h.toLowerCase(Locale.ROOT))
				.anyMatch(h -> h.equals(host) || host.endsWith("." + h));
		if (!allowed) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"Host not allowed for remote terminology: " + host + ". Allowed: " + myProperties.getAllowedHosts());
		}
		// Strip trailing slash for consistency
		String normalized = theUrl;
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}
}
