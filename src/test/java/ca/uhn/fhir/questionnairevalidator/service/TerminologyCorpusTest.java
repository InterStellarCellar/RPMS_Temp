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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus test: every fixture under {@code classpath:terminology/*.json} must produce at least
 * one <b>local terminology / binding</b> issue via the production package-loaded chain.
 *
 * <p>All fixtures are <b>valid JSON</b> and <b>clean FHIR parse</b>. They exercise code-system
 * lookups, value-set membership, UCUM unit checks, and “system not supported / unknown”
 * warnings from {@code CommonCodeSystemsTerminologyService} / in-memory expansion — with
 * <b>no remote terminology server</b>.
 *
 * <p>Uses {@link ValidationBeansConfig#localValidationSupportChain} so terminology packages
 * (THO, UV Extensions, SDC, xver) are available.
 *
 * <p>Expectations live in {@code expectations.tsv}. Duplicate filenames are allowed for
 * multi-error combo fixtures.
 */
class TerminologyCorpusTest {

	private static final String CORPUS_DIR = "terminology/";
	private static final String EXPECTATIONS = CORPUS_DIR + "expectations.tsv";

	private static final Pattern QUE_INVARIANT = Pattern.compile("Constraint failed:\\s*que-\\d");

	private static QuestionnaireValidationService ourService;
	private static FhirContext ourFhirContext;

	@BeforeAll
	static void init() {
		ValidationBeansConfig config = new ValidationBeansConfig();
		ourFhirContext = config.fhirContextR4();
		ValidationSupportChain chain = config.localValidationSupportChain(ourFhirContext);
		ourService = new QuestionnaireValidationService(ourFhirContext, chain);
	}

	record Case(String filename, int minIssues, String messageSubstring) {}

	static Stream<Case> corpusCases() throws IOException {
		InputStream in = TerminologyCorpusTest.class.getClassLoader().getResourceAsStream(EXPECTATIONS);
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

	@ParameterizedTest(name = "{0} \u2192 {2}")
	@MethodSource("corpusCases")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void fixture_reportsLocalTerminologyIssue(Case theCase) {
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

		// Local-only — empty remote URL
		ValidateResponse response = ourService.validate(json, "");
		List<ValidationIssueDto> issues = response.getIssues();

		List<ValidationIssueDto> matching = issues.stream()
				.filter(i -> i.getMessage() != null && i.getMessage().contains(theCase.messageSubstring()))
				.toList();
		assertTrue(
				matching.size() >= theCase.minIssues(),
				() -> theCase.filename() + " expected >= " + theCase.minIssues()
						+ " issue(s) containing \"" + theCase.messageSubstring()
						+ "\", found " + matching.size() + ". All issues:\n" + describeIssues(issues));

		assertFalse(
				QUE_INVARIANT.matcher(theCase.messageSubstring()).find(),
				() -> theCase.filename() + " expected substring looks like a que-* invariant — "
						+ "use que-invariant/ instead");

		assertTrue(
				matching.stream().anyMatch(i -> looksLikeTerminologyIssue(i.getMessage())),
				() -> theCase.filename() + " matched substring \"" + theCase.messageSubstring()
						+ "\" but none of the matching issues look terminology-related:\n"
						+ describeIssues(matching));
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
				"expectations.tsv filenames and classpath terminology/*.json files are out of sync");
	}

	/**
	 * Heuristic: messages that indicate binding / code-system / UCUM / unsupported-system checks.
	 * Kept intentionally broad so ValueSet membership warnings still count.
	 */
	static boolean looksLikeTerminologyIssue(String theMessage) {
		if (theMessage == null) {
			return false;
		}
		String m = theMessage.toLowerCase(Locale.ROOT);
		return m.contains("unknown code")
				|| m.contains("wrong display")
				|| m.contains("not supported")
				|| m.contains("codesystem")
				|| m.contains("code system")
				|| m.contains("value set")
				|| m.contains("valueset")
				|| m.contains("binding")
				|| m.contains("ucum")
				|| m.contains("unitsofmeasure")
				|| m.contains("unit '")
				|| m.contains("processing unit")
				|| m.contains("a code with no system")
				|| m.contains("none of the codings")
				|| m.contains("not found in the value set")
				|| m.contains("could not be expanded")
				|| m.contains("unable to expand")
				|| m.contains("terminology");
	}

	private static String readFixture(String theFilename) {
		String path = CORPUS_DIR + theFilename;
		try (InputStream in = TerminologyCorpusTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, "Missing fixture " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> listJsonFixtures() throws IOException {
		java.net.URL dir = TerminologyCorpusTest.class.getClassLoader().getResource(CORPUS_DIR);
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
