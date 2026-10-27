package ca.uhn.fhir.questionnairevalidator.service;

import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import ca.uhn.fhir.questionnairevalidator.dto.ValidationIssueDto;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.TimeType;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.UriType;
import org.hl7.fhir.r4.model.ValueSet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Authoring-time range, unit, and clinical-safety checks on a Questionnaire.
 *
 * <p>Always-on (no profile gate). Physiological and unit-dimension warnings
 * fire only when {@code item.code} matches the curated vitals table.
 */
class RangeAndUnitSafetyValidator {

	static final String URL_MIN_VALUE = "http://hl7.org/fhir/StructureDefinition/minValue";
	static final String URL_MAX_VALUE = "http://hl7.org/fhir/StructureDefinition/maxValue";
	static final String URL_SLIDER_STEP = "http://hl7.org/fhir/StructureDefinition/questionnaire-sliderStepValue";
	static final String URL_HIDDEN = "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden";
	static final String URL_UNIT = "http://hl7.org/fhir/StructureDefinition/questionnaire-unit";
	static final String URL_UNIT_OPTION = "http://hl7.org/fhir/StructureDefinition/questionnaire-unitOption";
	static final String URL_UNIT_VALUE_SET = "http://hl7.org/fhir/StructureDefinition/questionnaire-unitValueSet";
	static final String UCUM_SYSTEM = "http://unitsofmeasure.org";

	static final String MSG_MIN_GT_MAX = "minValue is greater than maxValue";
	static final String MSG_MIN_EQ_MAX = "minValue equals maxValue";
	static final String MSG_SLIDER = "sliderStepValue is larger than maxValue minus minValue";
	static final String MSG_VS_UNRESOLVED = "unitValueSet could not be resolved";
	static final String MSG_UNKNOWN_UCUM_PREFIX = "Unknown UCUM unit '";
	static final String MSG_DIMENSION = "unit dimension does not match concept (expected ";
	static final String MSG_PHYS = "declared value range is outside typical physiological bounds for ";
	static final String MSG_HIDDEN_REQUIRED = "hidden item should not be required";

	private final JsonElementLocator myLocator;
	private final IValidationSupport mySupport;
	private final ValidationSupportContext mySupportContext;
	private final List<ValidationIssueDto> myIssues = new ArrayList<>();

	private RangeAndUnitSafetyValidator(String theJson, IValidationSupport theSupport) {
		myLocator = new JsonElementLocator(theJson);
		mySupport = theSupport;
		mySupportContext = theSupport == null ? null : new ValidationSupportContext(theSupport);
	}

	static List<ValidationIssueDto> validate(
			Questionnaire theQuestionnaire, String theJson, IValidationSupport theSupport) {
		RangeAndUnitSafetyValidator validator = new RangeAndUnitSafetyValidator(theJson, theSupport);
		validator.walkItems(theQuestionnaire.getItem(), "Questionnaire");
		return validator.myIssues;
	}

	private void walkItems(List<Questionnaire.QuestionnaireItemComponent> theItems, String theParentPath) {
		for (int i = 0; i < theItems.size(); i++) {
			walkItem(theItems.get(i), theParentPath + ".item[" + i + "]");
		}
	}

	private void walkItem(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
		applyMinMaxSlider(theItem, thePath);
		applyUnitValueSet(theItem, thePath);
		applyUcumValidity(theItem, thePath);
		applyDimensionAndPhys(theItem, thePath);
		applyHiddenRequired(theItem, thePath);
		walkItems(theItem.getItem(), thePath);
	}

	private void applyMinMaxSlider(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
		Type min = firstExtValue(theItem, URL_MIN_VALUE);
		Type max = firstExtValue(theItem, URL_MAX_VALUE);
		Integer cmp = compareSameType(min, max);
		if (cmp != null) {
			if (cmp > 0) {
				addIssue(
						ResultSeverityEnum.ERROR,
						MSG_MIN_GT_MAX,
						extensionPath(thePath, theItem.getExtension(), URL_MIN_VALUE),
						URL_MIN_VALUE);
			} else if (cmp == 0) {
				addIssue(
						ResultSeverityEnum.WARNING,
						MSG_MIN_EQ_MAX,
						extensionPath(thePath, theItem.getExtension(), URL_MIN_VALUE),
						URL_MIN_VALUE);
			}
		}

		Type step = firstExtValue(theItem, URL_SLIDER_STEP);
		if (step == null || cmp == null || cmp > 0) {
			return;
		}
		BigDecimal stepValue = asNumber(step);
		BigDecimal minNumber = asNumber(min);
		BigDecimal maxNumber = asNumber(max);
		if (stepValue == null || minNumber == null || maxNumber == null) {
			return;
		}
		if (stepValue.compareTo(maxNumber.subtract(minNumber)) > 0) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_SLIDER,
					extensionPath(thePath, theItem.getExtension(), URL_SLIDER_STEP),
					URL_SLIDER_STEP);
		}
	}

	private void applyUnitValueSet(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
		if (mySupport == null) {
			return;
		}
		Extension extension = firstExt(theItem.getExtension(), URL_UNIT_VALUE_SET);
		if (extension == null) {
			return;
		}
		String canonical = canonicalValue(extension.getValue());
		if (canonical == null || canonical.isBlank()) {
			return;
		}
		if (fetchValueSet(canonical) == null) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_VS_UNRESOLVED,
					extensionPath(thePath, theItem.getExtension(), URL_UNIT_VALUE_SET),
					canonical);
		}
	}

	private void applyUcumValidity(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
		if (mySupport == null) {
			return;
		}
		checkUcumCoding(firstExt(theItem.getExtension(), URL_UNIT), thePath, theItem.getExtension(), URL_UNIT);
		List<Extension> extensions = theItem.getExtension();
		for (int i = 0; i < extensions.size(); i++) {
			Extension extension = extensions.get(i);
			if (!URL_UNIT_OPTION.equals(extension.getUrl())) {
				continue;
			}
			checkUcumCoding(extension, thePath + ".extension[" + i + "]", extensions, URL_UNIT_OPTION);
		}
	}

	private void checkUcumCoding(
			Extension theExtension, String thePathOrExtensionPath, List<Extension> theExtensions, String theUrl) {
		if (theExtension == null) {
			return;
		}
		Coding coding = asCoding(theExtension.getValue());
		if (coding == null || !isUcum(coding) || !coding.hasCode() || coding.getCode().isBlank()) {
			return;
		}
		String code = coding.getCode().trim();
		IValidationSupport.CodeValidationResult result;
		try {
			result = mySupport.validateCode(
					mySupportContext,
					new ConceptValidationOptions(),
					UCUM_SYSTEM,
					code,
					null,
					null);
		} catch (RuntimeException e) {
			return;
		}
		if (result != null && !result.isOk()) {
			addIssue(
					ResultSeverityEnum.ERROR,
					MSG_UNKNOWN_UCUM_PREFIX + code + "'",
					thePathOrExtensionPath.contains(".extension[")
							? thePathOrExtensionPath
							: extensionPath(thePathOrExtensionPath, theExtensions, theUrl),
					code);
		}
	}

	private void applyDimensionAndPhys(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
		VitalsBoundsTable.Spec spec = firstVitalsSpec(theItem);
		if (spec == null) {
			return;
		}

		for (Coding unit : declaredUnits(theItem)) {
			if (!isUcum(unit) || !unit.hasCode() || unit.getCode().isBlank()) {
				continue;
			}
			String dim = VitalsBoundsTable.dimensionOf(unit.getCode().trim());
			if (dim != null && !dim.equals(spec.dimension())) {
				addIssue(
						ResultSeverityEnum.WARNING,
						MSG_DIMENSION + spec.dimension() + ")",
						extensionPath(thePath, theItem.getExtension(), URL_UNIT),
						unit.getCode());
			}
		}

		Type min = firstExtValue(theItem, URL_MIN_VALUE);
		Type max = firstExtValue(theItem, URL_MAX_VALUE);
		String declaredUnit = declaredNumericUnit(theItem, min, max);
		BigDecimal convertedMin = convertBound(min, declaredUnit, spec);
		BigDecimal convertedMax = convertBound(max, declaredUnit, spec);
		boolean minLow = convertedMin != null && convertedMin.compareTo(spec.physMin()) < 0;
		boolean maxHigh = convertedMax != null && convertedMax.compareTo(spec.physMax()) > 0;
		boolean disjoint = (convertedMax != null && convertedMax.compareTo(spec.physMin()) < 0)
				|| (convertedMin != null && convertedMin.compareTo(spec.physMax()) > 0);
		if (minLow || maxHigh || disjoint) {
			String locationUrl = maxHigh || (disjoint && convertedMax != null) ? URL_MAX_VALUE : URL_MIN_VALUE;
			if (firstExt(theItem.getExtension(), locationUrl) == null) {
				locationUrl = firstExt(theItem.getExtension(), URL_MIN_VALUE) != null ? URL_MIN_VALUE : URL_MAX_VALUE;
			}
			addIssue(
					ResultSeverityEnum.WARNING,
					MSG_PHYS + spec.label(),
					extensionPath(thePath, theItem.getExtension(), locationUrl),
					locationUrl);
		}
	}

	private void applyHiddenRequired(Questionnaire.QuestionnaireItemComponent theItem, String thePath) {
		Type hidden = firstExtValue(theItem, URL_HIDDEN);
		if (!(hidden instanceof BooleanType booleanType) || !booleanType.booleanValue()) {
			return;
		}
		if (!theItem.getRequired()) {
			return;
		}
		addIssue(
				ResultSeverityEnum.WARNING,
				MSG_HIDDEN_REQUIRED,
				extensionPath(thePath, theItem.getExtension(), URL_HIDDEN),
				URL_HIDDEN);
	}

	private IBaseResource fetchValueSet(String theCanonical) {
		String url = theCanonical.trim();
		IBaseResource resource = safeFetchValueSet(url);
		if (resource != null) {
			return resource;
		}
		int pipe = url.indexOf('|');
		if (pipe > 0) {
			resource = safeFetchValueSet(url.substring(0, pipe));
			if (resource != null) {
				return resource;
			}
		}
		try {
			return mySupport.fetchResource(ValueSet.class, url);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private IBaseResource safeFetchValueSet(String theUrl) {
		try {
			return mySupport.fetchValueSet(theUrl);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static VitalsBoundsTable.Spec firstVitalsSpec(Questionnaire.QuestionnaireItemComponent theItem) {
		for (Coding coding : theItem.getCode()) {
			if (coding == null || !coding.hasSystem() || !coding.hasCode()) {
				continue;
			}
			VitalsBoundsTable.Spec spec = VitalsBoundsTable.lookup(coding.getSystem(), coding.getCode());
			if (spec != null) {
				return spec;
			}
		}
		return null;
	}

	private static List<Coding> declaredUnits(Questionnaire.QuestionnaireItemComponent theItem) {
		List<Coding> units = new ArrayList<>();
		Extension unit = firstExt(theItem.getExtension(), URL_UNIT);
		if (unit != null) {
			Coding coding = asCoding(unit.getValue());
			if (coding != null) {
				units.add(coding);
			}
		}
		for (Extension extension : theItem.getExtension()) {
			if (URL_UNIT_OPTION.equals(extension.getUrl())) {
				Coding coding = asCoding(extension.getValue());
				if (coding != null) {
					units.add(coding);
				}
			}
		}
		return units;
	}

	private static String declaredNumericUnit(
			Questionnaire.QuestionnaireItemComponent theItem, Type theMin, Type theMax) {
		String fromQuantity = quantityUnit(theMin);
		if (fromQuantity == null) {
			fromQuantity = quantityUnit(theMax);
		}
		if (fromQuantity != null) {
			return fromQuantity;
		}
		for (Coding coding : declaredUnits(theItem)) {
			if (isUcum(coding) && coding.hasCode() && !coding.getCode().isBlank()) {
				return coding.getCode().trim();
			}
		}
		return "";
	}

	private static BigDecimal convertBound(Type theBound, String theDeclaredUnit, VitalsBoundsTable.Spec theSpec) {
		BigDecimal raw = asNumber(theBound);
		if (raw == null) {
			return null;
		}
		String from = theDeclaredUnit == null ? "" : theDeclaredUnit;
		if (from.isEmpty()) {
			from = theSpec.physUnit();
		}
		return VitalsBoundsTable.convert(raw, from, theSpec.physUnit());
	}

	private static Integer compareSameType(Type theLeft, Type theRight) {
		if (theLeft == null || theRight == null) {
			return null;
		}
		if (!theLeft.fhirType().equals(theRight.fhirType())) {
			return null;
		}
		if (theLeft instanceof IntegerType left && theRight instanceof IntegerType right) {
			if (!left.hasValue() || !right.hasValue()) {
				return null;
			}
			return Integer.compare(left.getValue(), right.getValue());
		}
		if (theLeft instanceof DecimalType left && theRight instanceof DecimalType right) {
			if (!left.hasValue() || !right.hasValue()) {
				return null;
			}
			return left.getValue().compareTo(right.getValue());
		}
		if (theLeft instanceof Quantity left && theRight instanceof Quantity right) {
			if (!left.hasValue() || !right.hasValue() || !sameQuantityUnit(left, right)) {
				return null;
			}
			return left.getValue().compareTo(right.getValue());
		}
		if (theLeft instanceof DateType left && theRight instanceof DateType right) {
			if (!left.hasValue() || !right.hasValue()) {
				return null;
			}
			return left.getValue().compareTo(right.getValue());
		}
		if (theLeft instanceof DateTimeType left && theRight instanceof DateTimeType right) {
			if (!left.hasValue() || !right.hasValue()) {
				return null;
			}
			return left.getValue().compareTo(right.getValue());
		}
		if (theLeft instanceof TimeType left && theRight instanceof TimeType right) {
			if (!left.hasValue() || !right.hasValue()) {
				return null;
			}
			return left.getValue().compareTo(right.getValue());
		}
		return null;
	}

	private static boolean sameQuantityUnit(Quantity theLeft, Quantity theRight) {
		String leftSystem = theLeft.hasSystem() ? theLeft.getSystem() : "";
		String rightSystem = theRight.hasSystem() ? theRight.getSystem() : "";
		String leftCode = theLeft.hasCode() ? theLeft.getCode() : "";
		String rightCode = theRight.hasCode() ? theRight.getCode() : "";
		return leftSystem.equals(rightSystem) && leftCode.equals(rightCode);
	}

	private static BigDecimal asNumber(Type theType) {
		if (theType instanceof IntegerType integerType && integerType.hasValue()) {
			return BigDecimal.valueOf(integerType.getValue());
		}
		if (theType instanceof DecimalType decimalType && decimalType.hasValue()) {
			return decimalType.getValue();
		}
		if (theType instanceof Quantity quantity && quantity.hasValue()) {
			return quantity.getValue();
		}
		return null;
	}

	private static String quantityUnit(Type theType) {
		if (theType instanceof Quantity quantity && quantity.hasCode() && !quantity.getCode().isBlank()) {
			return quantity.getCode().trim();
		}
		return null;
	}

	private static Coding asCoding(Type theValue) {
		if (theValue instanceof Coding coding) {
			return coding;
		}
		return null;
	}

	private static boolean isUcum(Coding theCoding) {
		return theCoding.hasSystem() && UCUM_SYSTEM.equals(theCoding.getSystem().trim());
	}

	private static String canonicalValue(Type theValue) {
		if (theValue instanceof CanonicalType canonicalType) {
			return canonicalType.getValue();
		}
		if (theValue instanceof UriType uriType) {
			return uriType.getValue();
		}
		if (theValue instanceof StringType stringType) {
			return stringType.getValue();
		}
		return null;
	}

	private static Type firstExtValue(Questionnaire.QuestionnaireItemComponent theItem, String theUrl) {
		Extension extension = firstExt(theItem.getExtension(), theUrl);
		return extension == null ? null : extension.getValue();
	}

	private static Extension firstExt(List<Extension> theExtensions, String theUrl) {
		for (Extension extension : theExtensions) {
			if (theUrl.equals(extension.getUrl())) {
				return extension;
			}
		}
		return null;
	}

	private static int extensionIndex(List<Extension> theExtensions, String theUrl) {
		for (int i = 0; i < theExtensions.size(); i++) {
			if (theUrl.equals(theExtensions.get(i).getUrl())) {
				return i;
			}
		}
		return -1;
	}

	private static String extensionPath(String theParentPath, List<Extension> theExtensions, String theUrl) {
		int index = extensionIndex(theExtensions, theUrl);
		if (index >= 0) {
			return theParentPath + ".extension[" + index + "]";
		}
		return theParentPath;
	}

	private void addIssue(ResultSeverityEnum theSeverity, String theMessage, String thePath, String theValue) {
		ValidationIssueDto dto = new ValidationIssueDto();
		dto.setSeverity(theSeverity.getCode());
		dto.setMessage(theMessage);
		JsonElementLocator.Occurrence occurrence = myLocator.locate(thePath);
		if (occurrence == null && theValue != null) {
			occurrence = myLocator.locateByValueText(theValue);
		}
		if (occurrence != null) {
			dto.setLine(occurrence.line());
			dto.setColumn(occurrence.column());
			dto.setLocation(thePath);
		} else {
			dto.setLine(1);
			dto.setColumn(1);
			dto.setLocation(thePath == null || thePath.isBlank() ? "Questionnaire" : thePath);
		}
		myIssues.add(dto);
	}
}
