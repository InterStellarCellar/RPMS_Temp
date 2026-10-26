package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.questionnairevalidator.config.ValidationBeansConfig;
import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
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
 * Documentation corpus: fixtures under {@code classpath:known-gaps/*.json} are intentionally
 * flawed Questionnaires that the current validator does <b>not</b> reject with {@code error}/
 * {@code fatal}.
 *
 * <p>See {@code known-gaps/README.md} for gapKey definitions. When a fixture starts failing,
 * either move it to a failure corpus (behavior improved) or update the README / delete it
 * after confirming the new behavior is desired.
 */
class KnownGapsCorpusTest {

	private static final String CORPUS_DIR = "known-gaps/";
	private static final String EXPECTATIONS = CORPUS_DIR + "expectations.tsv";

	private static QuestionnaireValidationService ourService;
	private static FhirContext ourFhirContext;

	@BeforeAll
	static void init() {
		ValidationBeansConfig config = new ValidationBeansConfig();
		ourFhirContext = config.fhirContextR4();
		ValidationSupportChain chain = config.localValidationSupportChain(ourFhirContext);
		ourService = new QuestionnaireValidationService(ourFhirContext, chain);
	}

	record Case(String filename, int maxErrorFatal, String gapKey) {}

	static Stream<Case> corpusCases() throws IOException {
		InputStream in = KnownGapsCorpusTest.class.getClassLoader().getResourceAsStream(EXPECTATIONS);
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

	@ParameterizedTest(name = "{0} [{2}]")
	@MethodSource("corpusCases")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void fixture_documentsKnownGap_withoutErrorFatal(Case theCase) {
		String json = readFixture(theCase.filename());

		List<ValidationIssueDto> jsonErrors = JsonSyntaxChecker.check(json);
		assertTrue(
				jsonErrors.isEmpty(),
				() -> theCase.filename() + " has JSON syntax errors (belongs in json-malformed): "
						+ describeIssues(jsonErrors));

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

		ValidateResponse response = ourService.validate(json, "");
		List<ValidationIssueDto> errors = response.getIssues().stream()
				.filter(KnownGapsCorpusTest::isErrorOrFatal)
				.toList();

		assertTrue(
				errors.size() <= theCase.maxErrorFatal(),
				() -> theCase.filename() + " gapKey=" + theCase.gapKey()
						+ " expected <= " + theCase.maxErrorFatal()
						+ " error/fatal issue(s), found " + errors.size()
						+ ". If validation improved, move this fixture to a failure corpus.\n"
						+ describeIssues(errors));
	}

	@Test
	void expectations_coverEveryFixtureFile() throws IOException {
		Set<String> expectedFiles = corpusCases()
				.map(Case::filename)
				.collect(Collectors.toCollection(TreeSet::new));
		assertEquals(
				new TreeSet<>(listJsonFixtures()),
				expectedFiles,
				"expectations.tsv filenames and classpath known-gaps/*.json files are out of sync");
	}

	private static boolean isErrorOrFatal(ValidationIssueDto theIssue) {
		if (theIssue.getSeverity() == null) {
			return false;
		}
		String sev = theIssue.getSeverity().toLowerCase(Locale.ROOT);
		return "error".equals(sev) || "fatal".equals(sev);
	}

	private static String readFixture(String theFilename) {
		String path = CORPUS_DIR + theFilename;
		try (InputStream in = KnownGapsCorpusTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, "Missing fixture " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> listJsonFixtures() throws IOException {
		java.net.URL dir = KnownGapsCorpusTest.class.getClassLoader().getResource(CORPUS_DIR);
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
