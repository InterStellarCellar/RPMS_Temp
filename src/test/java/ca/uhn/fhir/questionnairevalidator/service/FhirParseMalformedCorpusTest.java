package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus test: every fixture under {@code classpath:fhir-parse-malformed/*.json} must produce
 * at least one FHIR parse error via {@link CollectingParserErrorHandler} (or a
 * {@link DataFormatException}), without hanging.
 *
 * <p>All fixtures are <b>valid JSON</b> (no syntax errors). They exercise the HAPI FHIR parser
 * layer specifically — not the JSON syntax checker and not the instance validator.
 *
 * <p>Expectations (min issue count + message substring) live in {@code expectations.tsv}.
 */
class FhirParseMalformedCorpusTest {

	private static final String CORPUS_DIR = "fhir-parse-malformed/";
	private static final String EXPECTATIONS = CORPUS_DIR + "expectations.tsv";

	private static FhirContext ourFhirContext;

	@BeforeAll
	static void initContext() {
		ourFhirContext = FhirContext.forR4();
	}

	record Case(String filename, int minIssues, String messageSubstring) {}

	static Stream<Case> corpusCases() throws IOException {
		InputStream in = FhirParseMalformedCorpusTest.class.getClassLoader().getResourceAsStream(EXPECTATIONS);
		assertNotNull(in, "Missing classpath resource " + EXPECTATIONS);
		List<Case> cases = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\t", 3);
				assertEquals(3, parts.length, "Bad expectations line: " + line);
				cases.add(new Case(parts[0], Integer.parseInt(parts[1]), parts[2]));
			}
		}
		assertFalse(cases.isEmpty(), "No corpus cases loaded from " + EXPECTATIONS);
		return cases.stream();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("corpusCases")
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void fixture_reportsFhirParseErrors(Case theCase) {
		String json = readFixture(theCase.filename());

		// Guard: the fixture must be valid JSON (no syntax errors)
		List<ValidationIssueDto> jsonErrors = JsonSyntaxChecker.check(json);
		assertTrue(
				jsonErrors.isEmpty(),
				() -> theCase.filename() + " has JSON syntax errors (should be valid JSON): " + describeIssues(jsonErrors));

		// Run HAPI parse with collecting handler (same path as QuestionnaireValidationService)
		CollectingParserErrorHandler handler = new CollectingParserErrorHandler();
		DataFormatException parseException = null;
		try {
			IParser parser = ourFhirContext.newJsonParser();
			parser.setParserErrorHandler(handler);
			parser.parseResource(json);
		} catch (DataFormatException e) {
			parseException = e;
		}

		List<CollectingParserErrorHandler.ParseError> errors = new ArrayList<>(handler.getErrors());
		if (parseException != null) {
			String msg = parseException.getMessage();
			if (errors.stream().noneMatch(e -> msg != null && msg.equals(e.message()))) {
				errors.add(QuestionnaireValidationService.parseErrorFromException(msg));
			}
		}

		// Must have at least minIssues errors
		assertTrue(
				errors.size() >= theCase.minIssues(),
				() -> theCase.filename() + " expected >= " + theCase.minIssues() + " parse errors, got "
						+ errors.size() + ": " + describeErrors(errors));

		// At least one error message must contain the expected substring
		assertTrue(
				errors.stream().anyMatch(e -> e.message() != null && e.message().contains(theCase.messageSubstring())),
				() -> theCase.filename() + " expected a message containing \"" + theCase.messageSubstring()
						+ "\", got: " + describeErrors(errors));

		// Verify issues can be located via JsonElementLocator (same as production path)
		List<ValidationIssueDto> issues = new JsonElementLocator(json).toIssues(errors);
		assertFalse(issues.isEmpty(), () -> theCase.filename() + " produced no issues from locator");
		for (ValidationIssueDto issue : issues) {
			assertEquals("error", issue.getSeverity(),
					() -> theCase.filename() + " issue severity should be 'error': " + issue.getMessage());
			assertNotNull(issue.getLine(),
					() -> theCase.filename() + " issue line is null: " + issue.getMessage());
			assertNotNull(issue.getColumn(),
					() -> theCase.filename() + " issue column is null: " + issue.getMessage());
			assertTrue(issue.getLine() >= 1,
					() -> theCase.filename() + " issue line < 1: " + issue.getMessage());
			assertTrue(issue.getColumn() >= 1,
					() -> theCase.filename() + " issue column < 1: " + issue.getMessage());
		}
	}

	@Test
	void expectations_coverEveryFixtureFile() throws IOException {
		List<String> expectedFiles = corpusCases().map(Case::filename).sorted().toList();
		List<String> onClasspath = listJsonFixtures();
		assertEquals(
				expectedFiles,
				onClasspath,
				"expectations.tsv and classpath fhir-parse-malformed/*.json are out of sync");
	}

	private static String readFixture(String theFilename) {
		String path = CORPUS_DIR + theFilename;
		try (InputStream in = FhirParseMalformedCorpusTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, "Missing fixture " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> listJsonFixtures() throws IOException {
		java.net.URL dir = FhirParseMalformedCorpusTest.class.getClassLoader().getResource(CORPUS_DIR);
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

	private static String describeErrors(List<CollectingParserErrorHandler.ParseError> theErrors) {
		StringBuilder out = new StringBuilder();
		for (CollectingParserErrorHandler.ParseError error : theErrors) {
			out.append('[').append(error.message()).append("] ");
		}
		return out.toString();
	}

	private static String describeIssues(List<ValidationIssueDto> theIssues) {
		StringBuilder out = new StringBuilder();
		for (ValidationIssueDto issue : theIssues) {
			out.append('[')
					.append(issue.getLine()).append(':').append(issue.getColumn())
					.append(' ').append(issue.getMessage())
					.append("] ");
		}
		return out.toString();
	}
}
