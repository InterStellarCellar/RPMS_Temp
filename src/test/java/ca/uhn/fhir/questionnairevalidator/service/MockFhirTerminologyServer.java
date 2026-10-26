package ca.uhn.fhir.questionnairevalidator.service;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal FHIR R4 terminology endpoint for corpus tests.
 *
 * <p>Implements enough of {@code CodeSystem}/{@code ValueSet} search and
 * {@code $validate-code} for HAPI {@code RemoteTerminologyServiceValidationSupport}.
 *
 * <p>Default policy: every searched CodeSystem/ValueSet exists; {@code $validate-code}
 * fails for codes matching {@link #isBadCode(String)} or displays matching
 * {@link #isWrongDisplay(String)}.
 */
final class MockFhirTerminologyServer implements AutoCloseable {

	enum Mode {
		/** Support all systems/value sets; fail bad codes / wrong displays. */
		DEFAULT,
		/** CodeSystem/ValueSet search always empty → remote does not claim support. */
		EMPTY_SEARCH,
		/** {@code $validate-code} returns HTTP 404. */
		VALIDATE_HTTP_404,
		/** {@code $validate-code} returns HTTP 400 with OperationOutcome. */
		VALIDATE_HTTP_400,
		/** {@code $validate-code} returns HTTP 500. */
		VALIDATE_HTTP_500,
		/** {@code $validate-code} returns 200 Parameters without {@code result}. */
		VALIDATE_MALFORMED_PARAMETERS,
		/** {@code $validate-code} always {@code result=false} with a fixed message. */
		REJECT_ALL_CODES,
		/** {@code $validate-code} always {@code result=true}. */
		ACCEPT_ALL_CODES
	}

	private static final Pattern CODE_PARAM = Pattern.compile(
			"\"name\"\\s*:\\s*\"code\"[\\s\\S]*?\"valueString\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
	private static final Pattern CODE_PARAM_CODE = Pattern.compile(
			"\"name\"\\s*:\\s*\"code\"[\\s\\S]*?\"valueCode\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
	private static final Pattern SYSTEM_PARAM = Pattern.compile(
			"\"name\"\\s*:\\s*\"system\"[\\s\\S]*?\"valueUri\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
	private static final Pattern SYSTEM_PARAM_STRING = Pattern.compile(
			"\"name\"\\s*:\\s*\"system\"[\\s\\S]*?\"valueString\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
	private static final Pattern DISPLAY_PARAM = Pattern.compile(
			"\"name\"\\s*:\\s*\"display\"[\\s\\S]*?\"valueString\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
	private static final Pattern URL_PARAM = Pattern.compile(
			"\"name\"\\s*:\\s*\"url\"[\\s\\S]*?\"valueUri\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

	private final HttpServer myServer;
	private final AtomicReference<Mode> myMode = new AtomicReference<>(Mode.DEFAULT);
	private final List<String> myRequestLog = new CopyOnWriteArrayList<>();

	private MockFhirTerminologyServer(HttpServer theServer) {
		myServer = theServer;
	}

	static MockFhirTerminologyServer start() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		MockFhirTerminologyServer mock = new MockFhirTerminologyServer(server);
		server.createContext("/", mock::handle);
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
		return mock;
	}

	String baseUrl() {
		return "http://127.0.0.1:" + myServer.getAddress().getPort() + "/fhir";
	}

	void setMode(Mode theMode) {
		myMode.set(theMode == null ? Mode.DEFAULT : theMode);
	}

	Mode getMode() {
		return myMode.get();
	}

	List<String> requestLog() {
		return new ArrayList<>(myRequestLog);
	}

	void clearRequestLog() {
		myRequestLog.clear();
	}

	@Override
	public void close() {
		myServer.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath();
		String query = exchange.getRequestURI().getQuery();
		String body = readBody(exchange.getRequestBody());
		String logLine = method + " " + path + (query == null ? "" : "?" + query);
		myRequestLog.add(logLine);

		try {
			if ("OPTIONS".equalsIgnoreCase(method)) {
				write(exchange, 204, "text/plain", "");
				return;
			}

			String fhirPath = stripFhirPrefix(path);
			Mode mode = myMode.get();

			// HAPI client often probes CapabilityStatement before operations.
			if (fhirPath.equals("metadata") || fhirPath.startsWith("metadata?")) {
				writeJson(exchange, 200, capabilityStatement());
				return;
			}

			if (fhirPath.startsWith("CodeSystem/$validate-code")
					|| fhirPath.startsWith("ValueSet/$validate-code")
					|| fhirPath.equals("CodeSystem/$validate-code")
					|| fhirPath.equals("ValueSet/$validate-code")) {
				handleValidateCode(exchange, body, mode, fhirPath.startsWith("ValueSet"));
				return;
			}

			if (fhirPath.startsWith("CodeSystem/$lookup") || fhirPath.equals("CodeSystem/$lookup")) {
				handleLookup(exchange, body, mode);
				return;
			}

			if (fhirPath.equals("CodeSystem") || fhirPath.startsWith("CodeSystem?")) {
				handleCodeSystemSearch(exchange, query, mode);
				return;
			}

			if (fhirPath.equals("ValueSet") || fhirPath.startsWith("ValueSet?")) {
				handleValueSetSearch(exchange, query, mode);
				return;
			}

			writeJson(exchange, 404, operationOutcome("not-found", "No route for " + path));
		} catch (Exception e) {
			writeJson(exchange, 500, operationOutcome("exception", e.getMessage()));
		}
	}

	private void handleCodeSystemSearch(HttpExchange exchange, String query, Mode mode) throws IOException {
		if (mode == Mode.EMPTY_SEARCH) {
			writeJson(exchange, 200, emptyBundle());
			return;
		}
		String url = firstQueryParam(query, "url");
		if (url == null || url.isBlank()) {
			url = "http://example.org/CodeSystem/unknown";
		}
		// Never advertise a ValueSet canonical as a CodeSystem.
		if (looksLikeValueSetUrl(url)) {
			writeJson(exchange, 200, emptyBundle());
			return;
		}
		writeJson(exchange, 200, codeSystemBundle(url));
	}

	private void handleValueSetSearch(HttpExchange exchange, String query, Mode mode) throws IOException {
		if (mode == Mode.EMPTY_SEARCH) {
			writeJson(exchange, 200, emptyBundle());
			return;
		}
		String url = firstQueryParam(query, "url");
		if (url == null || url.isBlank()) {
			writeJson(exchange, 200, emptyBundle());
			return;
		}
		/*
		 * Critical: HAPI probes ValueSet?url=<Coding.system> to detect mistaken VS URLs.
		 * If we return a ValueSet for http://loinc.org, validation reports
		 * "The Coding references a value set, not a code system" and never calls
		 * CodeSystem/$validate-code. Only claim ValueSets for VS-shaped canonicals.
		 */
		if (!looksLikeValueSetUrl(url) || isExplicitlyMissingCanonical(url)) {
			writeJson(exchange, 200, emptyBundle());
			return;
		}
		writeJson(exchange, 200, valueSetBundle(url));
	}

	/** Canonicals used by fixtures that should remain unresolved. */
	static boolean isExplicitlyMissingCanonical(String theUrl) {
		if (theUrl == null) {
			return false;
		}
		String u = theUrl.toLowerCase(Locale.ROOT);
		return u.contains("does-not-exist")
				|| u.contains("/missing")
				|| u.contains("bad-units")
				|| u.contains("not-found-vs");
	}

	/** Heuristic: VS canonicals usually contain {@code ValueSet} or {@code /vs/}. */
	static boolean looksLikeValueSetUrl(String theUrl) {
		if (theUrl == null) {
			return false;
		}
		String u = theUrl.toLowerCase(Locale.ROOT);
		return u.contains("valueset") || u.contains("/vs/") || u.contains("/vs?");
	}

	private void handleValidateCode(HttpExchange exchange, String body, Mode mode, boolean valueSetOp)
			throws IOException {
		switch (mode) {
			case VALIDATE_HTTP_404 -> {
				writeJson(exchange, 404, operationOutcome("not-found", "Mock Tx: validate-code not found"));
				return;
			}
			case VALIDATE_HTTP_400 -> {
				writeJson(exchange, 400, operationOutcome("invalid", "Mock Tx: invalid validate-code request"));
				return;
			}
			case VALIDATE_HTTP_500 -> {
				writeJson(exchange, 500, operationOutcome("exception", "Mock Tx: internal error"));
				return;
			}
			case VALIDATE_MALFORMED_PARAMETERS -> {
				writeJson(exchange, 200, "{\"resourceType\":\"Parameters\",\"parameter\":[]}");
				return;
			}
			case EMPTY_SEARCH -> {
				// Still answer validate-code if somehow called
				writeJson(exchange, 404, operationOutcome("not-found", "Mock Tx: empty-search mode"));
				return;
			}
			default -> {
				// continue
			}
		}

		String code = firstMatch(body, CODE_PARAM, CODE_PARAM_CODE);
		String system = firstMatch(body, SYSTEM_PARAM, SYSTEM_PARAM_STRING);
		String display = firstMatch(body, DISPLAY_PARAM);
		String valueSetUrl = firstMatch(body, URL_PARAM);

		if (mode == Mode.ACCEPT_ALL_CODES) {
			writeJson(exchange, 200, validateCodeSuccess(code, system, display));
			return;
		}

		if (mode == Mode.REJECT_ALL_CODES) {
			writeJson(
					exchange,
					200,
					validateCodeFailure(
							code,
							system,
							"Mock Tx rejected code '" + nullToEmpty(code) + "' in system '" + nullToEmpty(system)
									+ "'"));
			return;
		}

		// DEFAULT
		if (isWrongDisplay(display)) {
			String msg = "Wrong Display Name '" + display + "' for " + nullToEmpty(system) + "#"
					+ nullToEmpty(code) + ". Valid display is 'Correct Display'";
			writeJson(exchange, 200, validateCodeFailure(code, system, msg));
			return;
		}
		if (isBadCode(code)) {
			String msg;
			if (valueSetOp || (valueSetUrl != null && !valueSetUrl.isBlank())) {
				msg = "Unknown code '" + nullToEmpty(code) + "' in the ValueSet '" + nullToEmpty(valueSetUrl) + "'";
			} else {
				msg = "Unknown code '" + nullToEmpty(code) + "' in the CodeSystem '" + nullToEmpty(system) + "'";
			}
			writeJson(exchange, 200, validateCodeFailure(code, system, msg));
			return;
		}

		writeJson(exchange, 200, validateCodeSuccess(code, system, display));
	}

	private void handleLookup(HttpExchange exchange, String body, Mode mode) throws IOException {
		if (mode == Mode.VALIDATE_HTTP_404 || mode == Mode.EMPTY_SEARCH) {
			writeJson(exchange, 404, operationOutcome("not-found", "Mock Tx: lookup not found"));
			return;
		}
		String code = firstMatch(body, CODE_PARAM, CODE_PARAM_CODE);
		String system = firstMatch(body, SYSTEM_PARAM, SYSTEM_PARAM_STRING);
		if (isBadCode(code)) {
			writeJson(exchange, 404, operationOutcome("not-found", "Code not found: " + code));
			return;
		}
		String json = """
				{
				  "resourceType": "Parameters",
				  "parameter": [
				    {"name": "name", "valueString": "MockCodeSystem"},
				    {"name": "display", "valueString": "Mock display for %s"},
				    {"name": "system", "valueUri": "%s"}
				  ]
				}
				"""
				.formatted(escape(nullToEmpty(code)), escape(nullToEmpty(system)));
		writeJson(exchange, 200, json);
	}

	static boolean isBadCode(String theCode) {
		if (theCode == null || theCode.isBlank()) {
			return false;
		}
		String c = theCode.trim();
		String lower = c.toLowerCase(Locale.ROOT);
		if (c.equals("0000-0")
				|| c.equals("99999-9")
				|| c.equals("999999999999")
				|| c.equals("999999")
				|| c.equals("NOTAREAL")
				|| c.equals("xx-NOTREAL")
				|| lower.startsWith("not-a-")
				|| lower.startsWith("bad-")
				|| lower.equals("notarealucum")
				|| lower.equals("notarealucumcode")) {
			return true;
		}
		return false;
	}

	static boolean isWrongDisplay(String theDisplay) {
		if (theDisplay == null || theDisplay.isBlank()) {
			return false;
		}
		String d = theDisplay.trim();
		return d.startsWith("WRONG:")
				|| d.startsWith("Wrong ")
				|| d.equals("WRONG_DISPLAY")
				|| d.equals("Totally wrong")
				|| d.equals("Nope")
				|| d.equals("Man")
				|| d.equals("Wrong display name here")
				|| d.equals("Wrong panel name")
				|| d.equals("pounds");
	}

	private static String validateCodeSuccess(String code, String system, String display) {
		StringBuilder sb = new StringBuilder();
		sb.append("{\"resourceType\":\"Parameters\",\"parameter\":[");
		sb.append("{\"name\":\"result\",\"valueBoolean\":true}");
		if (system != null && !system.isBlank()) {
			sb.append(",{\"name\":\"system\",\"valueUri\":\"").append(escape(system)).append("\"}");
		}
		if (display != null && !display.isBlank()) {
			sb.append(",{\"name\":\"display\",\"valueString\":\"").append(escape(display)).append("\"}");
		} else if (code != null && !code.isBlank()) {
			sb.append(",{\"name\":\"display\",\"valueString\":\"Correct Display\"}");
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String validateCodeFailure(String code, String system, String message) {
		return """
				{
				  "resourceType": "Parameters",
				  "parameter": [
				    {"name": "result", "valueBoolean": false},
				    {"name": "message", "valueString": "%s"},
				    {"name": "system", "valueUri": "%s"}
				  ]
				}
				"""
				.formatted(escape(message), escape(nullToEmpty(system)));
	}

	private static String codeSystemBundle(String url) {
		return """
				{
				  "resourceType": "Bundle",
				  "type": "searchset",
				  "total": 1,
				  "entry": [{
				    "resource": {
				      "resourceType": "CodeSystem",
				      "id": "mock-cs",
				      "url": "%s",
				      "status": "active",
				      "content": "not-present",
				      "name": "MockCodeSystem"
				    }
				  }]
				}
				"""
				.formatted(escape(url));
	}

	private static String valueSetBundle(String url) {
		return """
				{
				  "resourceType": "Bundle",
				  "type": "searchset",
				  "total": 1,
				  "entry": [{
				    "resource": {
				      "resourceType": "ValueSet",
				      "id": "mock-vs",
				      "url": "%s",
				      "status": "active",
				      "name": "MockValueSet"
				    }
				  }]
				}
				"""
				.formatted(escape(url));
	}

	private static String emptyBundle() {
		return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0}";
	}

	private static String capabilityStatement() {
		return """
				{
				  "resourceType": "CapabilityStatement",
				  "status": "active",
				  "date": "2026-01-01",
				  "kind": "instance",
				  "fhirVersion": "4.0.1",
				  "format": ["application/fhir+json", "application/json"],
				  "rest": [{
				    "mode": "server",
				    "resource": [
				      {
				        "type": "CodeSystem",
				        "interaction": [{"code": "search-type"}, {"code": "read"}],
				        "operation": [{"name": "validate-code", "definition": "http://hl7.org/fhir/OperationDefinition/CodeSystem-validate-code"},
				                      {"name": "lookup", "definition": "http://hl7.org/fhir/OperationDefinition/CodeSystem-lookup"}]
				      },
				      {
				        "type": "ValueSet",
				        "interaction": [{"code": "search-type"}, {"code": "read"}],
				        "operation": [{"name": "validate-code", "definition": "http://hl7.org/fhir/OperationDefinition/ValueSet-validate-code"}]
				      }
				    ]
				  }]
				}
				""";
	}

	private static String operationOutcome(String code, String diagnostics) {
		return """
				{
				  "resourceType": "OperationOutcome",
				  "issue": [{
				    "severity": "error",
				    "code": "%s",
				    "diagnostics": "%s"
				  }]
				}
				"""
				.formatted(escape(code), escape(nullToEmpty(diagnostics)));
	}

	private static String stripFhirPrefix(String path) {
		String p = path == null ? "" : path;
		if (p.startsWith("/fhir/")) {
			return p.substring("/fhir/".length());
		}
		if (p.equals("/fhir")) {
			return "";
		}
		if (p.startsWith("/")) {
			return p.substring(1);
		}
		return p;
	}

	private static String firstQueryParam(String query, String name) {
		if (query == null || query.isBlank()) {
			return null;
		}
		for (String part : query.split("&")) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
			if (name.equals(key)) {
				return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private static String firstMatch(String body, Pattern... patterns) {
		if (body == null || body.isBlank()) {
			return null;
		}
		for (Pattern pattern : patterns) {
			Matcher m = pattern.matcher(body);
			if (m.find()) {
				return m.group(1);
			}
		}
		return null;
	}

	private static String readBody(InputStream in) throws IOException {
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}

	private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
		write(exchange, status, "application/fhir+json", json);
	}

	private static void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}
}
