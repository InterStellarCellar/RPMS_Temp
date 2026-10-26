package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus test: every fixture under {@code classpath:que-invariant/*.json} must produce at least
 * one HAPI instance-validation issue matching a {@code Constraint failed: que-N} message.
 *
 * <p>All fixtures are <b>valid JSON</b> and <b>valid FHIR parse</b>. They exercise
 * StructureDefinition invariants (que-0 through que-13) only.
 *
 * <p>Expectations (min matching issues + message substring) live in {@code expectations.tsv}.
 * Multiple rows may reference the same filename (for multi-invariant combo fixtures).
 */
class QueInvariantCorpusTest {

	private static final String CORPUS_DIR = "que-invariant/";
	private static final String EXPECTATIONS = CORPUS_DIR + "expectations.tsv";

	private static QuestionnaireValidationService ourService;

	@BeforeAll
	static void init() {
		FhirContext ctx = FhirContext.forR4();
		ValidationSupportChain chain = new ValidationSupportChain(
				new DefaultProfileValidationSupport(ctx),
				new InMemoryTerminologyServerValidationSupport(ctx),
				new CommonCodeSystemsTerminologyService(ctx),
				new SnapshotGeneratingValidationSupport(ctx));
		ourService = new QuestionnaireValidationService(ctx, chain);
	}

	record Case(String filename, int minIssues, String messageSubstring) {}

	static Stream<Case> corpusCases() throws IOException {
		InputStream in = QueInvariantCorpusTest.class.getClassLoader().getResourceAsStream(EXPECTATIONS);
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
	void fixture_reportsQueInvariantViolation(Case theCase) {
		String json = readFixture(theCase.filename());

		// Guard: must be valid JSON
		List<ValidationIssueDto> jsonErrors = JsonSyntaxChecker.check(json);
		assertTrue(
				jsonErrors.isEmpty(),
				() -> theCase.filename() + " has JSON syntax errors (should be valid JSON): "
						+ describeIssues(jsonErrors));

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
				"expectations.tsv filenames and classpath que-invariant/*.json files are out of sync");
	}

	private static String readFixture(String theFilename) {
		String path = CORPUS_DIR + theFilename;
		try (InputStream in = QueInvariantCorpusTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, "Missing fixture " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> listJsonFixtures() throws IOException {
		java.net.URL dir = QueInvariantCorpusTest.class.getClassLoader().getResource(CORPUS_DIR);
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
}
