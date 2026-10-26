package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
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
 * Quality / documentation corpus: fixtures under {@code classpath:location-quality/*.json}
 * produce validation issues whose {@code location} and {@code line}/{@code column} are
 * asserted according to {@code expectations.tsv}.
 *
 * <p>Covers accurate enrichment ({@link InvariantLocationEnricher}, parse locators) and
 * documents HAPI's coarse {@code line=1} + large-column reporting for many instance issues.
 * See {@code location-quality/README.md}.
 */
class LocationQualityCorpusTest {

	private static final String CORPUS_DIR = "location-quality/";
	private static final String EXPECTATIONS = CORPUS_DIR + "expectations.tsv";

	private static QuestionnaireValidationService ourService;

	@BeforeAll
	static void init() {
		ourService = CorpusTestSupport.service();
	}

	record Case(
			String filename,
			String messageSubstring,
			int minIssues,
			String locationSubstring,
			String lineMode,
			int lineArg) {}

	static Stream<Case> corpusCases() throws IOException {
		InputStream in = LocationQualityCorpusTest.class.getClassLoader().getResourceAsStream(EXPECTATIONS);
		assertNotNull(in, "Missing classpath resource " + EXPECTATIONS);
		List<Case> cases = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\t", 6);
				assertEquals(6, parts.length, "Bad expectations line (need 6 columns): " + line);
				cases.add(new Case(
						parts[0],
						parts[1],
						Integer.parseInt(parts[2]),
						parts[3],
						parts[4].toLowerCase(Locale.ROOT),
						Integer.parseInt(parts[5])));
			}
		}
		assertFalse(cases.isEmpty(), "No corpus cases loaded from " + EXPECTATIONS);
		return cases.stream();
	}

	@ParameterizedTest(name = "{0} → {1} ({4})")
	@MethodSource("corpusCases")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void fixture_reportsExpectedLocationQuality(Case theCase) {
		String json = readFixture(theCase.filename());
		ValidateResponse response = ourService.validate(json, "");
		List<ValidationIssueDto> matches = response.getIssues().stream()
				.filter(i -> i.getMessage() != null && i.getMessage().contains(theCase.messageSubstring()))
				.toList();

		assertTrue(
				matches.size() >= theCase.minIssues(),
				() -> theCase.filename() + " expected >= " + theCase.minIssues()
						+ " issue(s) containing \"" + theCase.messageSubstring()
						+ "\", found " + matches.size() + ". All issues:\n"
						+ describeIssues(response.getIssues()));

		for (ValidationIssueDto issue : matches) {
			if (!"-".equals(theCase.locationSubstring())) {
				assertNotNull(issue.getLocation(), () -> theCase.filename() + " matching issue has null location: "
						+ issue.getMessage());
				assertTrue(
						issue.getLocation().contains(theCase.locationSubstring()),
						() -> theCase.filename() + " location \"" + issue.getLocation()
								+ "\" does not contain \"" + theCase.locationSubstring()
								+ "\" for message: " + issue.getMessage());
			}
			assertLineMode(theCase, issue);
		}
	}

	private static void assertLineMode(Case theCase, ValidationIssueDto theIssue) {
		Integer line = theIssue.getLine();
		Integer column = theIssue.getColumn();
		assertNotNull(line, () -> theCase.filename() + " matching issue has null line: " + theIssue.getMessage());

		switch (theCase.lineMode()) {
			case "exact" -> assertEquals(
					theCase.lineArg(),
					line.intValue(),
					() -> theCase.filename() + " expected exact line " + theCase.lineArg()
							+ " but was " + line + ":" + column + " — " + theIssue.getMessage());
			case "min" -> assertTrue(
					line >= theCase.lineArg(),
					() -> theCase.filename() + " expected line >= " + theCase.lineArg()
							+ " but was " + line + ":" + column + " — " + theIssue.getMessage());
			case "coarse1" -> {
				assertEquals(
						1,
						line.intValue(),
						() -> theCase.filename() + " expected coarse line=1 but was " + line
								+ ":" + column + " — " + theIssue.getMessage()
								+ " (if locations were enriched, update expectations.tsv)");
				assertNotNull(
						column,
						() -> theCase.filename() + " coarse1 issue missing column: " + theIssue.getMessage());
				assertTrue(
						column > theCase.lineArg(),
						() -> theCase.filename() + " expected column > " + theCase.lineArg()
								+ " on line 1 but was " + line + ":" + column
								+ " — " + theIssue.getMessage());
			}
			default -> throw new IllegalArgumentException(
					"Unknown lineMode '" + theCase.lineMode() + "' for " + theCase.filename());
		}
	}

	@Test
	void expectations_coverEveryFixtureFile() throws IOException {
		Set<String> expectedFiles = corpusCases()
				.map(Case::filename)
				.collect(Collectors.toCollection(TreeSet::new));
		assertEquals(
				new TreeSet<>(listJsonFixtures()),
				expectedFiles,
				"expectations.tsv filenames and classpath location-quality/*.json files are out of sync");
	}

	private static String readFixture(String theFilename) {
		String path = CORPUS_DIR + theFilename;
		try (InputStream in = LocationQualityCorpusTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, "Missing fixture " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> listJsonFixtures() throws IOException {
		java.net.URL dir = LocationQualityCorpusTest.class.getClassLoader().getResource(CORPUS_DIR);
		assertNotNull(dir, "Missing classpath dir " + CORPUS_DIR);
		if (!"file".equals(dir.getProtocol())) {
			return corpusCases().map(Case::filename).sorted().distinct().toList();
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
					.append(" loc=").append(issue.getLocation())
					.append(' ').append(issue.getMessage())
					.append("]\n");
		}
		return out.toString();
	}
}
