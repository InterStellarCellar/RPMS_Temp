package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.questionnairevalidator.config.ValidationBeansConfig;
import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus test for remote terminology validation against a mocked FHIR Tx server.
 *
 * <p>Fixtures under {@code classpath:remote-terminology/*.json} are valid JSON and clean FHIR
 * parse. Validation runs with {@link QuestionnaireValidationService#validate(String, String)}
 * pointing at {@link MockFhirTerminologyServer}.
 *
 * <p>Expectations live in {@code expectations.tsv} (filename, minIssues, messageSubstring,
 * mockMode). Modes other than {@code DEFAULT} reconfigure the mock per case.
 */
class RemoteTerminologyCorpusTest {

	private static final String CORPUS_DIR = "remote-terminology/";
	private static final String EXPECTATIONS = CORPUS_DIR + "expectations.tsv";
	private static final String ACCEPT_ALL_SENTINEL = "ACCEPT_ALL_SENTINEL";
	private static final String EMPTY_SEARCH_NO_REMOTE_SENTINEL = "EMPTY_SEARCH_NO_REMOTE";
	private static final String UNRESOLVED_VS_SILENT_SENTINEL = "UNRESOLVED_VS_SILENT";

	private static MockFhirTerminologyServer ourMockTx;
	private static QuestionnaireValidationService ourService;
	private static FhirContext ourFhirContext;
	private static String ourRemoteUrl;

	@BeforeAll
	static void init() throws IOException {
		ourMockTx = MockFhirTerminologyServer.start();
		ourRemoteUrl = ourMockTx.baseUrl();

		ValidationBeansConfig config = new ValidationBeansConfig();
		ourFhirContext = config.fhirContextR4();
		ValidationSupportChain chain = config.localValidationSupportChain(ourFhirContext);
		ourService = new QuestionnaireValidationService(ourFhirContext, chain);
	}

	@AfterAll
	static void shutdown() {
		if (ourMockTx != null) {
			ourMockTx.close();
		}
	}

	@BeforeEach
	void resetMock() {
		ourMockTx.setMode(MockFhirTerminologyServer.Mode.DEFAULT);
		ourMockTx.clearRequestLog();
	}

	record Case(String filename, int minIssues, String messageSubstring, MockFhirTerminologyServer.Mode mockMode) {}

	static Stream<Case> corpusCases() throws IOException {
		InputStream in = RemoteTerminologyCorpusTest.class.getClassLoader().getResourceAsStream(EXPECTATIONS);
		assertNotNull(in, "Missing classpath resource " + EXPECTATIONS);
		List<Case> cases = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				// filename \t minIssues \t substring \t mockMode [\t # note...]
				String[] parts = line.split("\t", 5);
				assertTrue(parts.length >= 4, "Bad expectations line (need 4+ cols): " + line);
				MockFhirTerminologyServer.Mode mode =
						MockFhirTerminologyServer.Mode.valueOf(parts[3].trim());
				cases.add(new Case(parts[0], Integer.parseInt(parts[1]), parts[2], mode));
			}
		}
		assertFalse(cases.isEmpty(), "No corpus cases loaded from " + EXPECTATIONS);
		return cases.stream();
	}

	@ParameterizedTest(name = "{0} [{3}] → {2}")
	@MethodSource("corpusCases")
	@Timeout(value = 45, unit = TimeUnit.SECONDS)
	void fixture_reportsRemoteTerminologyIssue(Case theCase) {
		String json = readFixture(theCase.filename());

		List<ValidationIssueDto> jsonErrors = JsonSyntaxChecker.check(json);
		assertTrue(
				jsonErrors.isEmpty(),
				() -> theCase.filename() + " has JSON syntax errors: " + describeIssues(jsonErrors));

		CollectingParserErrorHandler handler = new CollectingParserErrorHandler();
		DataFormatException caught = null;
		try {
			IParser parser = ourFhirContext.newJsonParser();
			parser.setParserErrorHandler(handler);
			parser.parseResource(json);
		} catch (DataFormatException e) {
			caught = e;
		}
		final DataFormatException parseException = caught;
		assertTrue(
				handler.getErrors().isEmpty() && parseException == null,
				() -> theCase.filename() + " has FHIR parse errors (belongs in fhir-parse-malformed): "
						+ describeParseErrors(handler.getErrors(), parseException));

		ourMockTx.setMode(theCase.mockMode());
		ourMockTx.clearRequestLog();

		ValidateResponse response;
		try {
			response = ourService.validate(json, ourRemoteUrl);
		} catch (Exception ex) {
			String msg = ex.getMessage() == null ? "" : ex.getMessage();
			String lower = msg.toLowerCase(Locale.ROOT);
			if (theCase.mockMode() == MockFhirTerminologyServer.Mode.VALIDATE_MALFORMED_PARAMETERS
					&& theCase.messageSubstring().equals("result")) {
				assertTrue(
						lower.contains("result"),
						() -> theCase.filename() + " expected exception mentioning 'result', got: " + ex);
				return;
			}
			if (theCase.mockMode() == MockFhirTerminologyServer.Mode.VALIDATE_HTTP_500
					&& theCase.messageSubstring().equals("The Remote Terminology server")) {
				// HAPI may surface HTTP 500 as an exception rather than a validation issue.
				assertTrue(
						lower.contains("500") || lower.contains("internal") || msg.contains("Mock Tx"),
						() -> theCase.filename() + " expected HTTP 500-ish exception, got: " + ex);
				return;
			}
			throw ex;
		}

		List<ValidationIssueDto> issues = response.getIssues();

		if (ACCEPT_ALL_SENTINEL.equals(theCase.messageSubstring())) {
			boolean remoteFailure = issues.stream()
					.map(ValidationIssueDto::getMessage)
					.filter(m -> m != null)
					.anyMatch(m -> m.contains("The Remote Terminology server")
							&& m.toLowerCase(Locale.ROOT).contains("0000-0"));
			assertFalse(
					remoteFailure,
					() -> theCase.filename()
							+ " ACCEPT_ALL_CODES should not emit a remote failure for 0000-0. Issues:\n"
							+ describeIssues(issues)
							+ "\nRequests:\n"
							+ String.join("\n", ourMockTx.requestLog()));
			return;
		}

		if (EMPTY_SEARCH_NO_REMOTE_SENTINEL.equals(theCase.messageSubstring())
				|| UNRESOLVED_VS_SILENT_SENTINEL.equals(theCase.messageSubstring())) {
			boolean remoteWrapper = issues.stream()
					.map(ValidationIssueDto::getMessage)
					.filter(m -> m != null)
					.anyMatch(m -> m.contains("The Remote Terminology server"));
			assertFalse(
					remoteWrapper,
					() -> theCase.filename()
							+ " sentinel " + theCase.messageSubstring()
							+ " should not produce remote Tx wrappers. Issues:\n"
							+ describeIssues(issues)
							+ "\nRequests:\n"
							+ String.join("\n", ourMockTx.requestLog()));
			return;
		}

		long matchCount = issues.stream()
				.filter(i -> i.getMessage() != null && i.getMessage().contains(theCase.messageSubstring()))
				.count();
		assertTrue(
				matchCount >= theCase.minIssues(),
				() -> theCase.filename() + " expected >= " + theCase.minIssues()
						+ " issue(s) containing \"" + theCase.messageSubstring()
						+ "\", found " + matchCount + ". All issues:\n" + describeIssues(issues)
						+ "\nMock mode=" + theCase.mockMode() + " base=" + ourRemoteUrl
						+ "\nRequests:\n" + String.join("\n", ourMockTx.requestLog()));
	}

	@Test
	void expectations_coverEveryFixtureFile() throws IOException {
		Set<String> expectedFiles = corpusCases()
				.map(Case::filename)
				.collect(Collectors.toCollection(TreeSet::new));
		List<String> onClasspath = listJsonFixtures();
		assertEquals(
				new TreeSet<>(onClasspath),
				expectedFiles,
				"expectations.tsv filenames and classpath remote-terminology/*.json files are out of sync");
	}

	@Test
	void mockServer_baseUrlIsHttpLocalhost() {
		assertTrue(ourRemoteUrl.startsWith("http://127.0.0.1:"));
		assertTrue(ourRemoteUrl.endsWith("/fhir"));
	}

	private static String readFixture(String theFilename) {
		String path = CORPUS_DIR + theFilename;
		try (InputStream in = RemoteTerminologyCorpusTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, "Missing fixture " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> listJsonFixtures() throws IOException {
		java.net.URL dir = RemoteTerminologyCorpusTest.class.getClassLoader().getResource(CORPUS_DIR);
		assertNotNull(dir, "Missing classpath dir " + CORPUS_DIR);
		if (!"file".equals(dir.getProtocol())) {
			return corpusCases().map(Case::filename).sorted().toList();
		}
		final java.nio.file.Path path;
		try {
			path = java.nio.file.Path.of(dir.toURI());
		} catch (java.net.URISyntaxException e) {
			throw new IOException(e);
		}
		try (Stream<java.nio.file.Path> stream = java.nio.file.Files.list(path)) {
			return stream
					.map(p -> p.getFileName().toString())
					.filter(name -> name.endsWith(".json"))
					.sorted()
					.toList();
		}
	}

	private static String describeIssues(List<ValidationIssueDto> theIssues) {
		StringBuilder out = new StringBuilder();
		for (ValidationIssueDto issue : theIssues) {
			out.append("  [")
					.append(issue.getSeverity())
					.append(" @ ").append(issue.getLine()).append(':').append(issue.getColumn())
					.append(' ').append(issue.getMessage())
					.append("]\n");
		}
		return out.toString();
	}

	private static String describeParseErrors(
			List<CollectingParserErrorHandler.ParseError> theErrors, DataFormatException theException) {
		StringBuilder out = new StringBuilder();
		for (CollectingParserErrorHandler.ParseError error : theErrors) {
			out.append('[').append(error.message()).append("] ");
		}
		if (theException != null) {
			out.append("[DataFormatException: ").append(theException.getMessage()).append(']');
		}
		return out.toString();
	}
}
