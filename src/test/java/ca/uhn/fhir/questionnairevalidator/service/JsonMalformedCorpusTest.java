package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
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
 * Corpus test: every fixture under {@code classpath:json-malformed/*.json} must produce at least
 * one JSON-syntax ({@code fatal} / {@code location=JSON}) issue via {@link JsonSyntaxChecker},
 * without hanging. Expectations (min issue count + message substring) live in
 * {@code expectations.tsv}.
 */
class JsonMalformedCorpusTest {

	private static final String CORPUS_DIR = "json-malformed/";
	private static final String EXPECTATIONS = CORPUS_DIR + "expectations.tsv";

	record Case(String filename, int minIssues, String messageSubstring) {}

	static Stream<Case> corpusCases() throws IOException {
		InputStream in = JsonMalformedCorpusTest.class.getClassLoader().getResourceAsStream(EXPECTATIONS);
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
	@Timeout(value = 2, unit = TimeUnit.SECONDS)
	void fixture_reportsJsonSyntaxFatals(Case theCase) {
		String json = readFixture(theCase.filename());
		List<ValidationIssueDto> issues = JsonSyntaxChecker.check(json);

		assertTrue(
				issues.size() >= theCase.minIssues(),
				() -> theCase.filename() + " expected >= " + theCase.minIssues() + " issues, got "
						+ issues.size() + ": " + describe(issues));
		// Depth fixtures hit MAX_ERRORS (50) then abort; others should stay well below.
		int maxAllowed = theCase.filename().startsWith("51-") || theCase.filename().startsWith("52-")
				? 50
				: 49;
		assertTrue(
				issues.size() <= maxAllowed,
				() -> theCase.filename() + " too many issues (possible hang/spin): " + describe(issues));

		for (ValidationIssueDto issue : issues) {
			assertEquals("fatal", issue.getSeverity(), () -> theCase.filename() + ": " + describe(issues));
			assertEquals("JSON", issue.getLocation(), () -> theCase.filename() + ": " + describe(issues));
			assertNotNull(issue.getLine());
			assertNotNull(issue.getColumn());
			assertTrue(issue.getLine() >= 1);
			assertTrue(issue.getColumn() >= 1);
		}

		assertTrue(
				issues.stream().anyMatch(i -> i.getMessage() != null && i.getMessage().contains(theCase.messageSubstring())),
				() -> theCase.filename() + " expected a message containing \"" + theCase.messageSubstring()
						+ "\", got: " + describe(issues));
	}

	@Test
	void expectations_coverEveryFixtureFile() throws IOException {
		List<String> expectedFiles = corpusCases().map(Case::filename).sorted().toList();
		List<String> onClasspath = listJsonFixtures();
		assertEquals(
				expectedFiles,
				onClasspath,
				"expectations.tsv and classpath json-malformed/*.json are out of sync");
	}

	private static String readFixture(String theFilename) {
		String path = CORPUS_DIR + theFilename;
		try (InputStream in = JsonMalformedCorpusTest.class.getClassLoader().getResourceAsStream(path)) {
			assertNotNull(in, "Missing fixture " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<String> listJsonFixtures() throws IOException {
		// ClassLoader has no directory listing for jars; walk the filesystem when running from
		// target/test-classes, otherwise fall back to the expectations list.
		java.net.URL dir = JsonMalformedCorpusTest.class.getClassLoader().getResource(CORPUS_DIR);
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

	private static String describe(List<ValidationIssueDto> theIssues) {
		StringBuilder out = new StringBuilder();
		for (ValidationIssueDto issue : theIssues) {
			out.append('[')
					.append(issue.getLine())
					.append(':')
					.append(issue.getColumn())
					.append(' ')
					.append(issue.getMessage())
					.append("] ");
		}
		return out.toString();
	}
}
