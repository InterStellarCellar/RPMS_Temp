package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
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
 * Corpus test: every fixture under {@code classpath:instance-structural/*.json} must
 * produce at least one {@code FhirInstanceValidator} issue that is <b>not</b> a
 * {@code Constraint failed: que-*} invariant.
 *
 * <p>All fixtures are <b>valid JSON</b> and <b>clean FHIR parse</b> (zero
 * {@link CollectingParserErrorHandler} errors, no thrown {@link DataFormatException}).
 * They exercise post-parse instance-validation structural rules: missing required
 * elements, DomainResource/Narrative constraints, datatype invariants, format checks,
 * and enableWhen link resolution.
 *
 * <p>Expectations (min matching issues + message substring) live in {@code expectations.tsv}.
 * Multiple rows may reference the same filename (for multi-error combo fixtures).
 */
class InstanceStructuralCorpusTest {

	private static final String CORPUS_DIR = "instance-structural/";
	private static final String EXPECTATIONS = CORPUS_DIR + "expectations.tsv";

	private static final Pattern QUE_INVARIANT = Pattern.compile("Constraint failed: que-\\d");

	private static QuestionnaireValidationService ourService;
	private static FhirContext ourFhirContext;

	@BeforeAll
	static void init() {
		ourFhirContext = FhirContext.forR4();
		ValidationSupportChain chain = new ValidationSupportChain(
				new DefaultProfileValidationSupport(ourFhirContext),
				new InMemoryTerminologyServerValidationSupport(ourFhirContext),
				new CommonCodeSystemsTerminologyService(ourFhirContext),
				new SnapshotGeneratingValidationSupport(ourFhirContext));
		ourService = new QuestionnaireValidationService(ourFhirContext, chain);
	}

	record Case(String filename, int minIssues, String messageSubstring) {}

	static Stream<Case> corpusCases() throws IOException {
		InputStream in = InstanceStructuralCorpusTest.class.getClassLoader().getResourceAsStream(EXPECTATIONS);
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
	@Timeout(value = 15, unit = TimeUnit.SECONDS)
	void fixture_reportsInstanceStructuralIssue(Case theCase) {
		String json = readFixture(theCase.filename());

		// Guard 1: must be valid JSON
		List<ValidationIssueDto> jsonErrors = JsonSyntaxChecker.check(json);
		assertTrue(
				jsonErrors.isEmpty(),
				() -> theCase.filename() + " has JSON syntax errors (should be valid JSON): "
						+ describeIssues(jsonErrors));

		// Guard 2: must have zero FHIR parse errors (those belong in fhir-parse-malformed)
		CollectingParserErrorHandler handler = new CollectingParserErrorHandler();
		DataFormatException caughtParseException = null;
		try {
			IParser parser = ourFhirContext.newJsonParser();
			parser.setParserErrorHandler(handler);
			parser.parseResource(json);
		} catch (DataFormatException e) {
			caughtParseException = e;
		}
		final DataFormatException parseException = caughtParseException;
		assertTrue(
				handler.getErrors().isEmpty() && parseException == null,
				() -> theCase.filename() + " has FHIR parse errors (belongs in fhir-parse-malformed, not here): "
						+ describeParseErrors(handler.getErrors(), parseException));

		// Run full validation (local-only, no remote terminology)
		ValidateResponse response = ourService.validate(json, "");
		List<ValidationIssueDto> issues = response.getIssues();

		// At least minIssues issue messages must contain the expected substring
		long matchCount = issues.stream()
				.filter(i -> i.getMessage() != null && i.getMessage().contains(theCase.messageSubstring()))
				.count();
		assertTrue(
				matchCount >= theCase.minIssues(),
				() -> theCase.filename() + " expected >= " + theCase.minIssues()
						+ " issue(s) containing \"" + theCase.messageSubstring()
						+ "\", found " + matchCount + ". All issues:\n" + describeIssues(issues));

		// Guard 3: the matching issues must NOT be que-* invariants (those belong in que-invariant)
		boolean matchedSubstringIsQueInvariant = QUE_INVARIANT.matcher(theCase.messageSubstring()).find();
		assertFalse(
				matchedSubstringIsQueInvariant,
				() -> theCase.filename() + " expected substring looks like a que-* invariant — "
						+ "move this fixture to que-invariant/ instead");
	}

	@Test
	void expectations_coverEveryFixtureFile() throws IOException {
		Set<String> expectedFiles = corpusCases()
				.map(Case::filename)
				.collect(Collectors.toCollection(TreeSet::new));
		List<String> onClasspath = listJsonFixtures();
		Set<String> classpathSet = new TreeSet<>(onClasspath);
		assertEquals(
				classpathSet,
				expectedFiles,
				"expectations.tsv filenames and classpath instance-structural/*.json files are out of sync");
	}

	private static String readFixture(String theFilename) {
		String path = CORPUS_DIR + theFilename;
		try (InputStream in = InstanceStructuralCorpusTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, "Missing fixture " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> listJsonFixtures() throws IOException {
		java.net.URL dir = InstanceStructuralCorpusTest.class.getClassLoader().getResource(CORPUS_DIR);
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
