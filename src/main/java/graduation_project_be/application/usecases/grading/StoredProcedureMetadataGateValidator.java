package graduation_project_be.application.usecases.grading;

import graduation_project_be.domain.models.RoutineMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Validates the non-scoring metadata gate required before stored procedure test cases run. */
public final class StoredProcedureMetadataGateValidator {

    private StoredProcedureMetadataGateValidator() {
    }

    public static ValidationResult validate(
            List<RoutineMetadata> expectedRoutines,
            List<RoutineMetadata> actualRoutines,
            boolean caseSensitiveNames) {
        List<Violation> violations = new ArrayList<>();
        if (expectedRoutines == null || expectedRoutines.isEmpty()) {
            violations.add(configurationViolation(
                    "CONFIG_MISSING_EXPECTED_ROUTINE",
                    "Đề bài không có metadata Stored Procedure để kiểm tra contract.",
                    "STORED_PROCEDURE metadata",
                    null));
            return new ValidationResult(false, List.copyOf(violations));
        }

        List<RoutineMetadata> safeActualRoutines = actualRoutines == null ? List.of() : actualRoutines;
        for (RoutineMetadata expected : expectedRoutines) {
            if (!validateExpectedConfiguration(expected, violations)) {
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
                        "Thiếu Stored Procedure " + expected.getRoutineName() + ".",
                        expected.getRoutineName(),
                        null));
                continue;
            }

            String actualRoutineType = normalizeRoutineType(actual.getRoutineType());
            if (!"PROCEDURE".equals(actualRoutineType)) {
                violations.add(studentViolation(
                        "ROUTINE_TYPE_MISMATCH",
                        String.format("Sai loại routine %s: kỳ vọng PROCEDURE nhưng thực tế là %s.",
                                expected.getRoutineName(), display(actual.getRoutineType())),
                        "PROCEDURE",
                        display(actual.getRoutineType())));
            }

            int expectedParameterCount = parameterCount(expected);
            int actualParameterCount = parameterCount(actual);
            if (expectedParameterCount != actualParameterCount) {
                violations.add(studentViolation(
                        "PARAMETER_COUNT_MISMATCH",
                        String.format("Stored Procedure %s sai số lượng tham số: kỳ vọng %d nhưng thực tế là %d.",
                                expected.getRoutineName(), expectedParameterCount, actualParameterCount),
                        String.valueOf(expectedParameterCount),
                        String.valueOf(actualParameterCount)));
            }
        }

        return new ValidationResult(violations.isEmpty(), List.copyOf(violations));
    }

    private static boolean validateExpectedConfiguration(
            RoutineMetadata expected,
            List<Violation> violations) {
        if (expected == null || isBlank(expected.getRoutineName())) {
            violations.add(configurationViolation(
                    "CONFIG_MISSING_EXPECTED_NAME",
                    "Metadata đề bài thiếu tên Stored Procedure.",
                    "expected_name",
                    null));
            return false;
        }
        if (isBlank(expected.getRoutineType())) {
            violations.add(configurationViolation(
                    "CONFIG_MISSING_EXPECTED_TYPE",
                    "Metadata Stored Procedure " + expected.getRoutineName() + " thiếu expected_type.",
                    "STORED_PROCEDURE",
                    null));
            return false;
        }
        if (!"PROCEDURE".equals(normalizeRoutineType(expected.getRoutineType()))) {
            violations.add(configurationViolation(
                    "CONFIG_EXPECTED_TYPE_NOT_PROCEDURE",
                    "Metadata " + expected.getRoutineName() + " không khai báo loại STORED_PROCEDURE.",
                    "STORED_PROCEDURE",
                    display(expected.getRoutineType())));
            return false;
        }
        return true;
    }

    private static String normalizeRoutineType(String raw) {
        if (isBlank(raw)) {
            return "";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("STORED_PROCEDURE".equals(normalized) || "SQL_STORED_PROCEDURE".equals(normalized)) {
            return "PROCEDURE";
        }
        return normalized;
    }

    private static int parameterCount(RoutineMetadata routine) {
        return routine == null || routine.getParameters() == null ? 0 : routine.getParameters().size();
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
