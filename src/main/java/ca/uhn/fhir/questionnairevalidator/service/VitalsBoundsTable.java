package ca.uhn.fhir.questionnairevalidator.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Classpath table of curated vitals LOINCs: expected unit dimension and typical
 * physiological bounds. Used by {@link RangeAndUnitSafetyValidator} for policy
 * warnings (not FHIR invariants).
 */
final class VitalsBoundsTable {

	private static final String RESOURCE = "clinical-safety/vitals-bounds.tsv";
	private static final MathContext MC = MathContext.DECIMAL64;
	private static final BigDecimal FIVE = new BigDecimal("5");
	private static final BigDecimal NINE = new BigDecimal("9");
	private static final BigDecimal THIRTY_TWO = new BigDecimal("32");
	private static final BigDecimal KELVIN_OFFSET = new BigDecimal("273.15");
	private static final BigDecimal LB_TO_KG = new BigDecimal("0.45359237");
	private static final BigDecimal IN_TO_CM = new BigDecimal("2.54");
	private static final BigDecimal HUNDRED = new BigDecimal("100");
	private static final BigDecimal THOUSAND = new BigDecimal("1000");

	static final class Spec {
		private final String label;
		private final String dimension;
		private final BigDecimal physMin;
		private final BigDecimal physMax;
		private final String physUnit;

		private Spec(String theLabel, String theDimension, BigDecimal thePhysMin, BigDecimal thePhysMax, String thePhysUnit) {
			label = theLabel;
			dimension = theDimension;
			physMin = thePhysMin;
			physMax = thePhysMax;
			physUnit = thePhysUnit;
		}

		String label() {
			return label;
		}

		String dimension() {
			return dimension;
		}

		BigDecimal physMin() {
			return physMin;
		}

		BigDecimal physMax() {
			return physMax;
		}

		String physUnit() {
			return physUnit;
		}
	}

	private static final Map<String, Spec> BY_CONCEPT;
	private static final Map<String, String> UNIT_DIMENSION;

	static {
		Map<String, Spec> concepts = new LinkedHashMap<>();
		Map<String, String> dimensions = new HashMap<>(builtInUnitDimensions());
		try (InputStream in = VitalsBoundsTable.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Missing classpath resource " + RESOURCE);
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}
					String[] parts = line.split("\t", -1);
					if (parts.length < 8) {
						throw new IllegalStateException("Bad vitals-bounds row: " + line);
					}
					String system = parts[0].trim();
					String code = parts[1].trim();
					String label = parts[2].trim();
					String dimension = parts[3].trim();
					String examples = parts[4].trim();
					BigDecimal physMin = new BigDecimal(parts[5].trim());
					BigDecimal physMax = new BigDecimal(parts[6].trim());
					String physUnit = parts[7].trim();
					concepts.put(conceptKey(system, code), new Spec(label, dimension, physMin, physMax, physUnit));
					if (!examples.isBlank()) {
						for (String example : examples.split(",")) {
							String unit = example.trim();
							if (!unit.isEmpty()) {
								dimensions.putIfAbsent(unit, dimension);
							}
						}
					}
					if (!physUnit.isEmpty()) {
						dimensions.putIfAbsent(physUnit, dimension);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load " + RESOURCE, e);
		}
		BY_CONCEPT = Collections.unmodifiableMap(concepts);
		UNIT_DIMENSION = Collections.unmodifiableMap(dimensions);
	}

	private VitalsBoundsTable() {}

	static Spec lookup(String theSystem, String theCode) {
		if (theSystem == null || theCode == null) {
			return null;
		}
		return BY_CONCEPT.get(conceptKey(theSystem, theCode));
	}

	static String dimensionOf(String theUcum) {
		if (theUcum == null || theUcum.isBlank()) {
			return null;
		}
		return UNIT_DIMENSION.get(theUcum.trim());
	}

	/**
	 * Convert {@code theValue} from {@code theFromUnit} into {@code theToUnit}
	 * when both units share a known dimension and a simple factor exists.
	 * Returns {@code null} when conversion is not supported.
	 */
	static BigDecimal convert(BigDecimal theValue, String theFromUnit, String theToUnit) {
		if (theValue == null) {
			return null;
		}
		String from = theFromUnit == null ? "" : theFromUnit.trim();
		String to = theToUnit == null ? "" : theToUnit.trim();
		if (from.equals(to) || from.isEmpty() || to.isEmpty()) {
			return from.equals(to) || from.isEmpty() ? theValue : null;
		}
		String fromDim = dimensionOf(from);
		String toDim = dimensionOf(to);
		if (fromDim == null || !fromDim.equals(toDim)) {
			return null;
		}
		BigDecimal inCanonical = toCanonical(theValue, from, fromDim);
		if (inCanonical == null) {
			return null;
		}
		return fromCanonical(inCanonical, to, toDim);
	}

	private static BigDecimal toCanonical(BigDecimal theValue, String theUnit, String theDimension) {
		return switch (theDimension) {
			case "mass" -> switch (theUnit) {
				case "kg" -> theValue;
				case "g" -> theValue.divide(THOUSAND, MC);
				case "[lb_av]" -> theValue.multiply(LB_TO_KG, MC);
				default -> null;
			};
			case "length" -> switch (theUnit) {
				case "cm" -> theValue;
				case "m" -> theValue.multiply(HUNDRED, MC);
				case "mm" -> theValue.divide(new BigDecimal("10"), MC);
				case "[in_i]" -> theValue.multiply(IN_TO_CM, MC);
				default -> null;
			};
			case "temperature" -> switch (theUnit) {
				case "Cel" -> theValue;
				case "[degF]" -> theValue.subtract(THIRTY_TWO, MC).multiply(FIVE, MC).divide(NINE, MC);
				case "K" -> theValue.subtract(KELVIN_OFFSET, MC);
				default -> null;
			};
			case "frequency", "fraction", "ratio", "pressure" -> theValue;
			default -> null;
		};
	}

	private static BigDecimal fromCanonical(BigDecimal theCanonical, String theUnit, String theDimension) {
		return switch (theDimension) {
			case "mass" -> switch (theUnit) {
				case "kg" -> theCanonical;
				case "g" -> theCanonical.multiply(THOUSAND, MC);
				case "[lb_av]" -> theCanonical.divide(LB_TO_KG, MC);
				default -> null;
			};
			case "length" -> switch (theUnit) {
				case "cm" -> theCanonical;
				case "m" -> theCanonical.divide(HUNDRED, MC);
				case "mm" -> theCanonical.multiply(new BigDecimal("10"), MC);
				case "[in_i]" -> theCanonical.divide(IN_TO_CM, MC);
				default -> null;
			};
			case "temperature" -> switch (theUnit) {
				case "Cel" -> theCanonical;
				case "[degF]" -> theCanonical.multiply(NINE, MC).divide(FIVE, MC).add(THIRTY_TWO, MC);
				case "K" -> theCanonical.add(KELVIN_OFFSET, MC);
				default -> null;
			};
			case "frequency", "fraction", "ratio", "pressure" -> theCanonical;
			default -> null;
		};
	}

	private static Map<String, String> builtInUnitDimensions() {
		Map<String, String> map = new HashMap<>();
		putAll(map, "frequency", "/min", "{beats}/min", "{1}/min", "min-1");
		putAll(map, "fraction", "%");
		putAll(map, "temperature", "Cel", "[degF]", "K");
		putAll(map, "mass", "kg", "g", "mg", "[lb_av]", "[oz_av]");
		putAll(map, "length", "cm", "m", "mm", "[in_i]", "[ft_i]", "[ft_us]");
		putAll(map, "ratio", "kg/m2");
		putAll(map, "pressure", "mm[Hg]");
		return map;
	}

	private static void putAll(Map<String, String> theMap, String theDimension, String... theUnits) {
		for (String unit : theUnits) {
			theMap.put(unit, theDimension);
		}
	}

	private static String conceptKey(String theSystem, String theCode) {
		return theSystem.trim().toLowerCase(Locale.ROOT) + "#" + theCode.trim();
	}
}
