package graduation_project_be.application.usecases.grading;

import graduation_project_be.domain.models.RoutineMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates the non-scoring metadata gate required before FUNCTION test cases run. */
public final class FunctionMetadataContractValidator {

    private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
            Map.entry("INTEGER", "INT"),
            Map.entry("DEC", "DECIMAL"),
            Map.entry("NUMERIC", "DECIMAL"),
            Map.entry("CHARACTER VARYING", "VARCHAR"),
            Map.entry("NATIONAL CHARACTER VARYING", "NVARCHAR"),
            Map.entry("CHARACTER", "CHAR"),
            Map.entry("NATIONAL CHARACTER", "NCHAR"),
            Map.entry("BINARY VARYING", "VARBINARY"),
            Map.entry("DOUBLE PRECISION", "FLOAT"));

    private FunctionMetadataContractValidator() {
    }

    public static ValidationResult validate(
            List<RoutineMetadata> expectedRoutines,
            List<RoutineMetadata> actualRoutines,
            boolean caseSensitiveNames) {
        List<Violation> violations = new ArrayList<>();
        if (expectedRoutines == null || expectedRoutines.isEmpty()) {
            violations.add(configurationViolation(
                    "CONFIG_MISSING_EXPECTED_ROUTINE",
                    "Đề bài không có metadata Function để kiểm tra contract.",
                    "FUNCTION metadata",
                    null));
            return new ValidationResult(false, List.copyOf(violations));
        }

        List<RoutineMetadata> safeActualRoutines = actualRoutines == null ? List.of() : actualRoutines;
        for (RoutineMetadata expected : expectedRoutines) {
            validateExpectedConfiguration(expected, violations);
            if (expected == null || isBlank(expected.getRoutineName())) {
                continue;
            }

            RoutineMetadata actual = safeActualRoutines.stream()
                    .filter(candidate -> namesEqual(
                            expected.getRoutineName(), candidate.getRoutineName(), caseSensitiveNames))
                    .findFirst()
                    .orElse(null);
            if (actual == null) {
                violations.add(studentViolation(
                        "MISSING_ROUTINE",
                        "Thiếu Function " + expected.getRoutineName() + ".",
                        expected.getRoutineName(),
                        null));
                continue;
            }

            String expectedRoutineType = normalizeRoutineType(expected.getRoutineType());
            String actualRoutineType = normalizeRoutineType(actual.getRoutineType());
            if (!expectedRoutineType.isBlank() && !expectedRoutineType.equals(actualRoutineType)) {
                violations.add(studentViolation(
                        "ROUTINE_TYPE_MISMATCH",
                        String.format("Sai loại routine %s: kỳ vọng FUNCTION nhưng thực tế là %s.",
                                expected.getRoutineName(), display(actual.getRoutineType())),
                        "FUNCTION",
                        display(actual.getRoutineType())));
            }

            String expectedReturnType = normalizeDataType(expected.getDataType());
            if (!expectedReturnType.isBlank()
                    && !dataTypesEqual(expected.getDataType(), actual.getDataType())) {
                violations.add(studentViolation(
                        "RETURN_TYPE_MISMATCH",
                        String.format("Function %s sai kiểu trả về: kỳ vọng %s nhưng thực tế là %s.",
                                expected.getRoutineName(), display(expected.getDataType()), display(actual.getDataType())),
                        display(expected.getDataType()),
                        display(actual.getDataType())));
            }

            List<RoutineMetadata.ParameterMetadata> expectedParameters = safeParameters(expected);
            List<RoutineMetadata.ParameterMetadata> actualParameters = safeParameters(actual);
            if (expectedParameters.size() != actualParameters.size()) {
                violations.add(studentViolation(
                        "PARAMETER_COUNT_MISMATCH",
                        String.format("Function %s sai số lượng tham số: kỳ vọng %d nhưng thực tế là %d.",
                                expected.getRoutineName(), expectedParameters.size(), actualParameters.size()),
                        String.valueOf(expectedParameters.size()),
                        String.valueOf(actualParameters.size())));
                continue;
            }

            for (int index = 0; index < expectedParameters.size(); index++) {
                RoutineMetadata.ParameterMetadata expectedParameter = expectedParameters.get(index);
                RoutineMetadata.ParameterMetadata actualParameter = actualParameters.get(index);
                int ordinal = index + 1;

                String expectedParameterType = expectedParameter == null ? null : expectedParameter.getDataType();
                String actualParameterType = actualParameter == null ? null : actualParameter.getDataType();
                String expectedType = normalizeDataType(expectedParameterType);
                if (!expectedType.isBlank() && !dataTypesEqual(expectedParameterType, actualParameterType)) {
                    violations.add(studentViolation(
                            "PARAMETER_TYPE_MISMATCH",
                            String.format("Function %s sai kiểu tham số thứ %d: kỳ vọng %s nhưng thực tế là %s.",
                                    expected.getRoutineName(), ordinal,
                                    display(expectedParameterType), display(actualParameterType)),
                            display(expectedParameterType),
                            display(actualParameterType)));
                }

                String expectedParameterMode = expectedParameter == null ? null : expectedParameter.getParameterMode();
                String actualParameterMode = actualParameter == null ? null : actualParameter.getParameterMode();
                String expectedMode = normalizeParameterMode(expectedParameterMode);
                String actualMode = normalizeParameterMode(actualParameterMode);
                if (!expectedMode.isBlank() && !expectedMode.equals(actualMode)) {
                    violations.add(studentViolation(
                            "PARAMETER_MODE_MISMATCH",
                            String.format("Function %s sai mode tham số thứ %d: kỳ vọng %s nhưng thực tế là %s.",
                                    expected.getRoutineName(), ordinal,
                                    display(expectedParameterMode),
                                    display(actualParameterMode)),
                            display(expectedParameterMode),
                            display(actualParameterMode)));
                }
            }
        }

        return new ValidationResult(violations.isEmpty(), List.copyOf(violations));
    }

    static String normalizeDataType(String raw) {
        if (isBlank(raw)) {
            return "";
        }
        String normalized = raw.trim()
                .replace("[", "")
                .replace("]", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        int argumentsStart = normalized.indexOf('(');
        String baseType = argumentsStart >= 0
                ? normalized.substring(0, argumentsStart).trim()
                : normalized;
        String canonicalBaseType = TYPE_ALIASES.getOrDefault(baseType, baseType);
        if (argumentsStart < 0) {
            return canonicalBaseType;
        }

        String arguments = normalized.substring(argumentsStart)
                .replaceAll("\\s+", "");
        return canonicalBaseType + arguments;
    }

    static boolean dataTypesEqual(String expectedRaw, String actualRaw) {
        String expected = normalizeDataType(expectedRaw);
        String actual = normalizeDataType(actualRaw);
        if (expected.isBlank() || actual.isBlank()) {
            return expected.equals(actual);
        }

        int expectedArgumentsStart = expected.indexOf('(');
        if (expectedArgumentsStart < 0) {
            int actualArgumentsStart = actual.indexOf('(');
            String actualBase = actualArgumentsStart >= 0
                    ? actual.substring(0, actualArgumentsStart)
                    : actual;
            return expected.equals(actualBase);
        }
        return expected.equals(actual);
    }

    static String normalizeParameterMode(String raw) {
        if (isBlank(raw)) {
            return "";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return "IN OUT".equals(normalized) ? "INOUT" : normalized;
    }

    static String normalizeRoutineType(String raw) {
        if (isBlank(raw)) {
            return "";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("SCALAR_FUNCTION".equals(normalized)
                || "TABLE_VALUED_FUNCTION".equals(normalized)
                || "SQL_SCALAR_FUNCTION".equals(normalized)
                || "SQL_TABLE_VALUED_FUNCTION".equals(normalized)) {
            return "FUNCTION";
        }
        return normalized;
    }

    private static void validateExpectedConfiguration(
            RoutineMetadata expected,
            List<Violation> violations) {
        if (expected == null || isBlank(expected.getRoutineName())) {
            violations.add(configurationViolation(
                    "CONFIG_MISSING_EXPECTED_NAME",
                    "Metadata đề bài thiếu tên Function.",
                    "expected_name",
                    null));
            return;
        }
        if (isBlank(expected.getRoutineType())) {
            violations.add(configurationViolation(
                    "CONFIG_MISSING_EXPECTED_TYPE",
                    "Metadata Function " + expected.getRoutineName() + " thiếu expected_type.",
                    "FUNCTION",
                    null));
        } else if (!"FUNCTION".equals(normalizeRoutineType(expected.getRoutineType()))) {
            violations.add(configurationViolation(
                    "CONFIG_EXPECTED_TYPE_NOT_FUNCTION",
                    "Metadata " + expected.getRoutineName() + " không khai báo loại FUNCTION.",
                    "FUNCTION",
                    display(expected.getRoutineType())));
        }
        if (isBlank(expected.getDataType())) {
            violations.add(configurationViolation(
                    "CONFIG_MISSING_EXPECTED_RETURN_TYPE",
                    "Metadata Function " + expected.getRoutineName() + " thiếu kiểu trả về.",
                    "expected_return_type",
                    null));
        }

        List<RoutineMetadata.ParameterMetadata> parameters = safeParameters(expected);
        for (int index = 0; index < parameters.size(); index++) {
            RoutineMetadata.ParameterMetadata parameter = parameters.get(index);
            int ordinal = index + 1;
            if (parameter == null || isBlank(parameter.getDataType())) {
                violations.add(configurationViolation(
                        "CONFIG_MISSING_EXPECTED_PARAMETER_TYPE",
                        String.format("Metadata Function %s thiếu kiểu của tham số thứ %d.",
                                expected.getRoutineName(), ordinal),
                        "parameters[].expected_type",
                        null));
            }
            if (parameter == null || isBlank(parameter.getParameterMode())) {
                violations.add(configurationViolation(
                        "CONFIG_MISSING_EXPECTED_PARAMETER_MODE",
                        String.format("Metadata Function %s thiếu mode của tham số thứ %d.",
                                expected.getRoutineName(), ordinal),
                        "parameters[].expected_mode",
                        null));
            }
        }
    }

    private static List<RoutineMetadata.ParameterMetadata> safeParameters(RoutineMetadata routine) {
        return routine == null || routine.getParameters() == null ? List.of() : routine.getParameters();
    }

    private static boolean namesEqual(String expected, String actual, boolean caseSensitive) {
        if (expected == null || actual == null) {
            return false;
        }
        return caseSensitive ? expected.equals(actual) : expected.equalsIgnoreCase(actual);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String display(String value) {
        return isBlank(value) ? "<không có>" : value;
    }

    private static Violation configurationViolation(
            String code, String message, String expected, String actual) {
        return new Violation(code, message, expected, actual, true);
    }

    private static Violation studentViolation(
            String code, String message, String expected, String actual) {
        return new Violation(code, message, expected, actual, false);
    }

    public record ValidationResult(boolean passed, List<Violation> violations) {
    }

    public record Violation(
            String code,
            String message,
            String expected,
            String actual,
            boolean configurationError) {
    }
}
