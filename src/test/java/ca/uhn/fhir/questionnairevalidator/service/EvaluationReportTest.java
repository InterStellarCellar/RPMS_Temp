package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.questionnairevalidator.dto.ValidateResponse;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evaluation harness for the thesis: replays every corpus fixture through the production
 * validator and reports coverage per Thesis A taxonomy category, false-positive rate on the
 * negative controls, and latency percentiles. Writes {@code target/evaluation-report.md}.
 *
 * <p>Opt-in because it revalidates ~900 fixtures (several minutes):
 * <pre>
 * mvn -o test -Dtest=EvaluationReportTest -Devaluation=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Definitions used in the report:
 * <ul>
 *   <li><b>Detection case</b>: a failure-corpus row with {@code minIssues > 0}. Detected when at
 *       least {@code minIssues} issues contain the expected substring (same rule as the corpus tests).</li>
 *   <li><b>Control case</b>: a failure-corpus row with {@code minIssues == 0}, or a {@code valid/}
 *       fixture. False positive when the forbidden substring appears / {@code ok} is false.</li>
 *   <li><b>Known gap</b>: a {@code known-gaps/} fixture whose gapKey names something the validator
 *       should catch but does not. Counted as a miss in the coverage denominator so the figure is
 *       honest about documented blind spots.</li>
 *   <li><b>By design</b>: a {@code known-gaps/} fixture whose gapKey is in {@link #BY_DESIGN_GAPS}:
 *       spec SHOULD rules and runtime semantics the validator deliberately leaves alone so it is
 *       not over-restrictive. Listed in the report, excluded from the coverage denominator.</li>
 *   <li><b>Coverage</b> = detected / (detection cases + known gaps).</li>
 * </ul>
 * {@code remote-terminology/} (needs the mock server) and {@code location-quality/} (measures
 * position precision, not detection) are excluded.
 */
class EvaluationReportTest {

	/** Thesis A error taxonomy. */
	enum Category {
		STRUCTURAL("Structural / schema"),
		LINKID("linkId integrity"),
		ENABLEWHEN("enableWhen logic"),
		TYPE("Type mismatch"),
		BINDING("Value set bindings"),
		FHIRPATH("FHIRPath expressions"),
		CARDINALITY("Cardinality"),
		EXTRACT("Extraction mapping"),
		MONITORING("Monitoring-specific");

		final String label;

		Category(String label) {
			this.label = label;
		}
	}

	/** Failure corpora: filename, minIssues, substring. */
	private static final List<String> FAILURE_DIRS = List.of(
			"json-malformed", "fhir-parse-malformed", "instance-structural", "que-invariant",
			"profile-canonical", "sdc", "uv-extensions", "terminology", "expression-fhirpath",
			"type-consistency", "observation-extract", "range-unit-safety", "coding-trendability",
			"monitoring-expression-ux");

	private static final List<String> COUNT_TOTAL_DIRS = List.of("json-malformed", "fhir-parse-malformed");

	private static final int WARMUP = 30;

	record Row(String dir, String filename, int minIssues, String substring) {}

	/** gapKeys in known-gaps/expectations.tsv that are deliberate leniency, not blind spots. */
	static final Set<String> BY_DESIGN_GAPS = Set.of("authoring-soft-rule", "sdc-runtime-not-executed");

	static final class Tally {
		int detectionCases;
		int detected;
		int knownGaps;
		int byDesign;
		final List<String> byDesignList = new ArrayList<>();
		int controlCases;
		int falsePositives;
		final List<String> misses = new ArrayList<>();
		final List<String> fps = new ArrayList<>();
	}

	@Test
	@EnabledIfSystemProperty(named = "evaluation", matches = "true")
	void generateReport() throws IOException {
		QuestionnaireValidationService service = CorpusTestSupport.service();
		Map<Category, Tally> tallies = new EnumMap<>(Category.class);
		for (Category c : Category.values()) {
			tallies.put(c, new Tally());
		}
		List<Long> latenciesNanos = new ArrayList<>();
		List<Long> validLatenciesNanos = new ArrayList<>();
		List<String> externalResults = new ArrayList<>();
		int calls = 0;

		// Warm-up so JIT/caches do not skew latency numbers.
		List<Row> valid = readRows("valid");
		for (Row r : valid.subList(0, Math.min(WARMUP, valid.size()))) {
			service.validate(readFixture(r), "");
		}

		for (String dir : FAILURE_DIRS) {
			for (Row r : readRows(dir)) {
				long start = System.nanoTime();
				ValidateResponse response = service.validate(readFixture(r), "");
				latenciesNanos.add(System.nanoTime() - start);
				calls++;
				long matches = response.getIssues().stream()
						.map(ValidationIssueDto::getMessage)
						.filter(m -> m != null && m.contains(r.substring()))
						.count();
				Tally t = tallies.get(classify(r));
				if (r.minIssues() == 0) {
					t.controlCases++;
					if (matches > 0) {
						t.falsePositives++;
						t.fps.add(r.dir() + "/" + r.filename());
					}
				} else {
					t.detectionCases++;
					// json-malformed / fhir-parse-malformed rows mean "at least minIssues issues in total,
					// one of them containing the substring" (multi-error fixtures); others mean
					// "at least minIssues issues containing the substring".
					boolean countsTotal = COUNT_TOTAL_DIRS.contains(r.dir());
					boolean detected = countsTotal
							? matches >= 1 && response.getIssues().size() >= r.minIssues()
							: matches >= r.minIssues();
					if (detected) {
						t.detected++;
					} else {
						t.misses.add(r.dir() + "/" + r.filename());
					}
				}
			}
		}

		for (Row r : valid) {
			long start = System.nanoTime();
			ValidateResponse response = service.validate(readFixture(r), "");
			long elapsed = System.nanoTime() - start;
			latenciesNanos.add(elapsed);
			validLatenciesNanos.add(elapsed);
			calls++;
			Tally t = tallies.get(Category.STRUCTURAL);
			t.controlCases++;
			if (!response.isOk()) {
				t.falsePositives++;
				t.fps.add("valid/" + r.filename());
			}
		}

		for (Row r : readRows("known-gaps")) {
			Tally t = tallies.get(classify(r));
			if (BY_DESIGN_GAPS.contains(r.substring())) {
				t.byDesign++;
				t.byDesignList.add("known-gaps/" + r.filename() + " (" + r.substring() + ")");
			} else {
				t.knownGaps++;
				t.misses.add("known-gaps/" + r.filename() + " (" + r.substring() + ")");
			}
		}

		// External negative control: the example Questionnaires shipped inside the SDC 4.0.0 package.
		NpmPackage sdc = NpmPackage.fromPackage(
				EvaluationReportTest.class.getClassLoader().getResourceAsStream("packages/hl7.fhir.uv.sdc-4.0.0.tgz"));
		for (String name : sdc.list("example")) {
			if (!name.startsWith("Questionnaire-")) continue;
			String json = new String(sdc.load("example", name).readAllBytes(), StandardCharsets.UTF_8);
			ValidateResponse response = service.validate(json, "");
			calls++;
			String firstError = response.getIssues().stream()
					.filter(i -> "error".equals(i.getSeverity()) || "fatal".equals(i.getSeverity()))
					.map(ValidationIssueDto::getMessage).findFirst().orElse("");
			externalResults.add((response.isOk() ? "ok    " : "ERROR ") + name
					+ (firstError.isEmpty() ? "" : " : " + abbreviate(firstError)));
		}

		assertTrue(calls > 0, "No fixtures evaluated");
		String report = render(tallies, latenciesNanos, validLatenciesNanos, externalResults, calls);
		Path out = Path.of("target", "evaluation-report.md");
		Files.createDirectories(out.getParent());
		Files.writeString(out, report, StandardCharsets.UTF_8);
		System.out.println(report);
	}

	// ---- taxonomy mapping -------------------------------------------------------------------

	private static final Pattern FHIRPATH_HINT =
			Pattern.compile("Expression|fhirpath|fhir-query|referenceFilter|cqf-library|cql", Pattern.CASE_INSENSITIVE);
	private static final Pattern EXTRACT_HINT =
			Pattern.compile("xtract|StructureMap|launchContext|assembleContext", Pattern.CASE_INSENSITIVE);
	private static final Pattern ENABLEWHEN_HINT = Pattern.compile("enableWhen|circular|enableBehavior", Pattern.CASE_INSENSITIVE);
	private static final Pattern BINDING_HINT =
			Pattern.compile("TerminologyServer|jurisdiction|language|ucum|valueSet", Pattern.CASE_INSENSITIVE);

	static Category classify(Row r) {
		String f = r.filename();
		String s = r.substring();
		int n = fixtureNumber(f);
		switch (r.dir()) {
			case "json-malformed", "fhir-parse-malformed", "profile-canonical":
				return Category.STRUCTURAL;
			case "instance-structural":
				if (n < 20) return Category.CARDINALITY;
				if (n < 40) return s.contains("linkId") ? Category.LINKID : Category.ENABLEWHEN;
				if (n < 50) return s.contains("minimum required") ? Category.CARDINALITY : Category.TYPE;
				return Category.STRUCTURAL;
			case "que-invariant":
				if (s.contains("que-2")) return Category.LINKID;
				if (s.contains("que-7") || s.contains("que-12")) return Category.ENABLEWHEN;
				if (s.contains("que-5") || s.contains("que-10")) return Category.TYPE;
				if (s.startsWith("que-1") || s.contains("que-13")) return Category.CARDINALITY;
				return Category.STRUCTURAL;
			case "sdc":
				if (s.contains("sdc-behave-2")) return Category.ENABLEWHEN;
				if (s.contains("sdc-inv1")) return Category.FHIRPATH;
				if (n < 10) return Category.CARDINALITY;
				if (n >= 70 && n < 80) return Category.EXTRACT;
				return Category.STRUCTURAL;
			case "uv-extensions":
				if (s.contains("but found type")) return Category.TYPE;
				if (s.contains("var-1")) return Category.CARDINALITY;
				if (n >= 80 && n < 100) return Category.BINDING;
				if (n >= 130 && n < 150) return Category.FHIRPATH;
				if (n >= 10 && n < 80) return Category.TYPE;
				return Category.STRUCTURAL;
			case "terminology", "remote-terminology":
				return Category.BINDING;
			case "expression-fhirpath":
				return Category.FHIRPATH;
			case "type-consistency":
				return Category.TYPE;
			case "observation-extract":
				return Category.EXTRACT;
			case "range-unit-safety", "coding-trendability":
				return Category.MONITORING;
			case "monitoring-expression-ux":
				return ENABLEWHEN_HINT.matcher(s).find() ? Category.ENABLEWHEN : Category.MONITORING;
			case "known-gaps":
				if (FHIRPATH_HINT.matcher(f).find()) return Category.FHIRPATH;
				if (EXTRACT_HINT.matcher(f).find()) return Category.EXTRACT;
				if (ENABLEWHEN_HINT.matcher(f).find()) return Category.ENABLEWHEN;
				if (f.contains("linkId")) return Category.LINKID;
				if (BINDING_HINT.matcher(f).find()) return Category.BINDING;
				return Category.STRUCTURAL;
			default:
				return Category.STRUCTURAL;
		}
	}

	private static int fixtureNumber(String filename) {
		int i = 0;
		while (i < filename.length() && Character.isDigit(filename.charAt(i))) i++;
		return i == 0 ? -1 : Integer.parseInt(filename.substring(0, i));
	}

	// ---- report -----------------------------------------------------------------------------

	private static String abbreviate(String s) {
		return s.length() > 140 ? s.substring(0, 140) + "..." : s;
	}

	private static String render(Map<Category, Tally> tallies, List<Long> nanos, List<Long> validNanos,
			List<String> externalResults, int calls) {
		Collections.sort(nanos);
		Collections.sort(validNanos);
		StringBuilder sb = new StringBuilder();
		sb.append("# Evaluation report\n\n");
		sb.append("Generated by `EvaluationReportTest`. ").append(calls).append(" validator calls (after ")
				.append(WARMUP).append(" warm-up calls). Local chain only, no remote terminology.\n\n");
		sb.append("This is internal corpus coverage: the fraction of author-defined expectation rows the ")
				.append("validator satisfies, with the documented known gaps in the denominator. It measures rule ")
				.append("completeness against the taxonomy, not real-world recall. Known-gap misses are asserted ")
				.append("from known-gaps/expectations.tsv (max_error_fatal = 0), not re-observed here.\n\n");
		sb.append("Strict coverage = detected / (detection cases + all known gaps). Lenient coverage excludes ")
				.append("known-gaps/ rows with gapKey ").append(BY_DESIGN_GAPS)
				.append(" (deliberate leniency: spec SHOULD rules and populate/extract runtime semantics), ")
				.append("listed under 'By design'. The by-design set was fixed before the final run.\n\n");

		sb.append("## Coverage by taxonomy category\n\n");
		sb.append("| Category | Detection cases | Detected | Known gaps | By design | Lenient coverage | Controls | False positives |\n");
		sb.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
		int totDet = 0, totHit = 0, totGap = 0, totDesign = 0, totCtl = 0, totFp = 0;
		for (Category c : Category.values()) {
			Tally t = tallies.get(c);
			totDet += t.detectionCases;
			totHit += t.detected;
			totGap += t.knownGaps;
			totDesign += t.byDesign;
			totCtl += t.controlCases;
			totFp += t.falsePositives;
			sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %s | %d | %d |\n",
					c.label, t.detectionCases, t.detected, t.knownGaps, t.byDesign,
					pct(t.detected, t.detectionCases + t.knownGaps), t.controlCases, t.falsePositives));
		}
		sb.append(String.format(Locale.ROOT, "| **Total** | %d | %d | %d | %d | **%s** | %d | **%d** |\n\n",
				totDet, totHit, totGap, totDesign, pct(totHit, totDet + totGap), totCtl, totFp));
		sb.append(String.format(Locale.ROOT,
				"**Strict coverage (headline, every known gap counted as a miss): %s.** Lenient: %s.\n\n",
				pct(totHit, totDet + totGap + totDesign), pct(totHit, totDet + totGap)));
		sb.append(String.format(Locale.ROOT,
				"False-positive rate on controls: %d / %d = %s\n\n", totFp, totCtl, pct(totFp, totCtl)));

		sb.append("## Latency (ms, per validate call)\n\n");
		sb.append("| p50 | p95 | p99 | max | mean |\n|---:|---:|---:|---:|---:|\n");
		sb.append(String.format(Locale.ROOT, "| %.0f | %.0f | %.0f | %.0f | %.0f |\n\n",
				ms(percentile(nanos, 50)), ms(percentile(nanos, 95)), ms(percentile(nanos, 99)),
				ms(nanos.get(nanos.size() - 1)), ms(nanos.stream().mapToLong(Long::longValue).sum() / (double) nanos.size())));
		sb.append("Thesis A target: <= 300 ms. ").append(ms(percentile(nanos, 95)) <= 300 ? "p95 meets target." : "p95 exceeds target.")
				.append("\n\n");
		sb.append("All rows above include JSON/FHIR parse fast-fails. Full pipeline on the ")
				.append(validNanos.size()).append(" `valid/` fixtures only (every layer runs): ");
		sb.append(String.format(Locale.ROOT, "p50 %.0f ms, p95 %.0f ms, p99 %.0f ms, max %.0f ms.\n\n",
				ms(percentile(validNanos, 50)), ms(percentile(validNanos, 95)), ms(percentile(validNanos, 99)),
				ms(validNanos.get(validNanos.size() - 1))));
		sb.append("Single run, one machine; not stratified by Questionnaire size.\n\n");

		long extOk = externalResults.stream().filter(e -> e.startsWith("ok")).count();
		sb.append("## External controls: example Questionnaires in the SDC 4.0.0 package\n\n");
		sb.append("Not authored by this project. ").append(extOk).append(" of ").append(externalResults.size())
				.append(" validate with ok = true. Rows marked ERROR are either validator false positives or ")
				.append("genuine problems in the IG examples; each needs a manual verdict.\n\n```\n");
		externalResults.forEach(e -> sb.append(e).append('\n'));
		sb.append("```\n\n");

		sb.append("## Misses (undetected cases and known gaps)\n\n");
		for (Category c : Category.values()) {
			Tally t = tallies.get(c);
			if (t.misses.isEmpty()) continue;
			sb.append("### ").append(c.label).append('\n');
			t.misses.forEach(m -> sb.append("- ").append(m).append('\n'));
			sb.append('\n');
		}
		sb.append("## By design (deliberately not flagged, excluded from coverage)\n\n");
		for (Category c : Category.values()) {
			Tally t = tallies.get(c);
			if (t.byDesignList.isEmpty()) continue;
			sb.append("### ").append(c.label).append('\n');
			t.byDesignList.forEach(m -> sb.append("- ").append(m).append('\n'));
			sb.append('\n');
		}
		sb.append("## False positives\n\n");
		boolean any = false;
		for (Tally t : tallies.values()) {
			for (String f : t.fps) {
				sb.append("- ").append(f).append('\n');
				any = true;
			}
		}
		if (!any) sb.append("None.\n");
		return sb.toString();
	}

	private static String pct(int num, int den) {
		return den == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0 * num / den);
	}

	private static double ms(double nanos) {
		return nanos / 1_000_000.0;
	}

	private static long percentile(List<Long> sorted, int p) {
		int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
		return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
	}

	// ---- io ----------------------------------------------------------------------------------

	private static List<Row> readRows(String dir) throws IOException {
		InputStream in = EvaluationReportTest.class.getClassLoader().getResourceAsStream(dir + "/expectations.tsv");
		assertTrue(in != null, "Missing " + dir + "/expectations.tsv");
		List<Row> rows = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) continue;
				String[] parts = line.split("\t", 3);
				rows.add(new Row(dir, parts[0], Integer.parseInt(parts[1].trim()), parts.length > 2 ? parts[2] : ""));
			}
		}
		return rows;
	}

	private static String readFixture(Row r) throws IOException {
		try (InputStream in = EvaluationReportTest.class.getClassLoader().getResourceAsStream(r.dir() + "/" + r.filename())) {
			assertTrue(in != null, "Missing fixture " + r.dir() + "/" + r.filename());
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
