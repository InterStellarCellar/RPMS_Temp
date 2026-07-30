package ca.uhn.fhir.questionnairevalidator.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.RemoteTerminologyServiceValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Configuration
@EnableConfigurationProperties(TerminologyProperties.class)
public class ValidationBeansConfig {

	private static final Logger ourLog = LoggerFactory.getLogger(ValidationBeansConfig.class);

	/** Classpath location of FHIR NPM .tgz packages (from src/main/packages). */
	private static final String PACKAGE_CLASSPATH_PATTERN = "classpath*:packages/*.tgz";

	/**
	 * Core FHIR CodeSystem URLs (single path segment under hl7.org/fhir) that must stay R4.
	 * Cross-version packages ship R5 copies of these (e.g. item-type with {@code coding} instead of
	 * {@code choice}) which otherwise shadow R4 and break Questionnaire validation.
	 */
	private static final Pattern CORE_FHIR_CODE_SYSTEM_URL =
			Pattern.compile("^https?://hl7\\.org/fhir/[A-Za-z0-9.-]+$");

	/** Always skip these from packages — R4 core / DefaultProfile owns them. */
	private static final Set<String> ALWAYS_SKIP_PACKAGE_CODE_SYSTEMS = Set.of(
			"http://hl7.org/fhir/item-type");

	@Bean
	public FhirContext fhirContextR4() {
		return FhirContext.forR4();
	}

	/**
	 * Local-only support chain (no remote Tx). Remote is added per-request when selected.
	 * Loads any {@code packages/*.tgz} IG packages from the classpath, filtering out R5 core
	 * CodeSystems that would conflict with R4.
	 */
	@Bean
	public ValidationSupportChain localValidationSupportChain(FhirContext theFhirContext) {
		PrePopulatedValidationSupport npmPackages = loadClasspathPackages(theFhirContext);

		return new ValidationSupportChain(
				npmPackages,
				new DefaultProfileValidationSupport(theFhirContext),
				new InMemoryTerminologyServerValidationSupport(theFhirContext),
				new CommonCodeSystemsTerminologyService(theFhirContext),
				new SnapshotGeneratingValidationSupport(theFhirContext));
	}

	public static IValidationSupport withOptionalRemote(
			FhirContext theFhirContext, ValidationSupportChain theLocalChain, String theRemoteUrl) {
		if (theRemoteUrl == null || theRemoteUrl.isBlank()) {
			return theLocalChain;
		}
		return new ValidationSupportChain(
				theLocalChain,
				new RemoteTerminologyServiceValidationSupport(theFhirContext, theRemoteUrl.trim()));
	}

	private static PrePopulatedValidationSupport loadClasspathPackages(FhirContext theFhirContext) {
		PrePopulatedValidationSupport support = new PrePopulatedValidationSupport(theFhirContext);
		try {
			Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PACKAGE_CLASSPATH_PATTERN);
			Arrays.sort(resources, (a, b) -> {
				String fa = a.getFilename();
				String fb = b.getFilename();
				if (fa == null) {
					return fb == null ? 0 : 1;
				}
				if (fb == null) {
					return -1;
				}
				return fa.compareTo(fb);
			});

			if (resources.length == 0) {
				ourLog.warn("No FHIR NPM packages found at {}", PACKAGE_CLASSPATH_PATTERN);
				return support;
			}

			IParser parser = theFhirContext.newJsonParser();
			parser.setParserErrorHandler(new LenientErrorHandler(false));

			for (Resource resource : resources) {
				String filename = resource.getFilename();
				if (filename == null) {
					continue;
				}
				ourLog.info("Loading FHIR NPM package (filtered): classpath:packages/{}", filename);
				try (InputStream in = resource.getInputStream()) {
					loadPackageFiltered(support, parser, NpmPackage.fromPackage(in), filename);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load FHIR NPM packages from classpath", e);
		}
		return support;
	}

	private static void loadPackageFiltered(
			PrePopulatedValidationSupport theSupport,
			IParser theParser,
			NpmPackage thePackage,
			String thePackageFilename)
			throws IOException {
		Map<String, NpmPackage.NpmPackageFolder> folders = thePackage.getFolders();
		if (!folders.containsKey("package")) {
			ourLog.warn("Package {} has no 'package' folder; skipping", thePackageFilename);
			return;
		}

		NpmPackage.NpmPackageFolder packageFolder = folders.get("package");
		int added = 0;
		int skipped = 0;
		for (String nextFile : packageFolder.listFiles()) {
			if (!nextFile.toLowerCase(Locale.ROOT).endsWith(".json")) {
				continue;
			}
			byte[] bytes = packageFolder.getContent().get(nextFile);
			if (bytes == null) {
				continue;
			}
			String input = new String(bytes, StandardCharsets.UTF_8);
			IBaseResource resource;
			try {
				resource = theParser.parseResource(input);
			} catch (Exception e) {
				ourLog.debug("Skipping unparseable {} in {}: {}", nextFile, thePackageFilename, e.toString());
				continue;
			}

			if (shouldSkipPackageResource(resource, thePackageFilename)) {
				skipped++;
				ourLog.debug(
						"Filtered {} {} from {}",
						resource.fhirType(),
						canonicalUrl(resource),
						thePackageFilename);
				continue;
			}
			theSupport.addResource(resource);
			added++;
		}
		ourLog.info(
				"Package {}: added {} resources, filtered out {}",
				thePackageFilename,
				added,
				skipped);
	}

	/**
	 * Drop package CodeSystems that would override R4 core definitions (notably xver's R5
	 * {@code item-type}). StructureDefinitions and IG/THO CodeSystems are kept.
	 */
	static boolean shouldSkipPackageResource(IBaseResource theResource, String thePackageFilename) {
		if (!"CodeSystem".equals(theResource.fhirType())) {
			return false;
		}
		String url = canonicalUrl(theResource);
		if (url == null || url.isBlank()) {
			return false;
		}
		if (ALWAYS_SKIP_PACKAGE_CODE_SYSTEMS.contains(url)) {
			return true;
		}
		// xver ships many R5 copies of core FHIR CodeSystems under the same URLs as R4
		if (thePackageFilename != null
				&& thePackageFilename.toLowerCase(Locale.ROOT).contains("xver")
				&& CORE_FHIR_CODE_SYSTEM_URL.matcher(url).matches()) {
			return true;
		}
		return false;
	}

	private static String canonicalUrl(IBaseResource theResource) {
		if (theResource instanceof org.hl7.fhir.r4.model.MetadataResource metadata) {
			return metadata.getUrl();
		}
		return null;
	}
}
