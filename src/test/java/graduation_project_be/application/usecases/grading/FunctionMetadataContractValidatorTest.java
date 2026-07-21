package graduation_project_be.application.usecases.grading;

import graduation_project_be.domain.models.RoutineMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionMetadataContractValidatorTest {

    @Test
    void legacyBaseTypeStillMatchesParameterizedActualType() {
        FunctionMetadataContractValidator.ValidationResult result = validate(
                routine("VARCHAR", "DECIMAL", "IN"),
                routine("VARCHAR(50)", "DECIMAL(15,2)", "IN"));

        assertTrue(result.passed());
    }

    @Test
    void rejectsCharacterLengthMismatchWhenRubricSpecifiesLength() {
        FunctionMetadataContractValidator.ValidationResult result = validate(
                routine("VARCHAR(10)", "INT", "IN"),
                routine("VARCHAR(200)", "INT", "IN"));

        assertFalse(result.passed());
        assertTrue(result.violations().stream()
                .anyMatch(violation -> "RETURN_TYPE_MISMATCH".equals(violation.code())));
    }

    @Test
    void rejectsNumericPrecisionOrScaleMismatch() {
        FunctionMetadataContractValidator.ValidationResult result = validate(
                routine("INT", "DECIMAL(15,2)", "IN"),
                routine("INT", "DECIMAL(18,0)", "IN"));

        assertFalse(result.passed());
        assertTrue(result.violations().stream()
                .anyMatch(violation -> "PARAMETER_TYPE_MISMATCH".equals(violation.code())));
    }

    @Test
    void aliasesKeepTheirArgumentsWhenCompared() {
        FunctionMetadataContractValidator.ValidationResult result = validate(
                routine("INT", "NUMERIC(15,2)", "IN"),
                routine("INT", "DECIMAL(15,2)", "IN"));

        assertTrue(result.passed());
    }

    private FunctionMetadataContractValidator.ValidationResult validate(
            RoutineMetadata expected,
            RoutineMetadata actual) {
        return FunctionMetadataContractValidator.validate(
                List.of(expected), List.of(actual), false);
    }

    private RoutineMetadata routine(String returnType, String parameterType, String parameterMode) {
        return RoutineMetadata.builder()
                .routineName("FN_Test")
                .routineType("FUNCTION")
                .dataType(returnType)
                .parameters(List.of(RoutineMetadata.ParameterMetadata.builder()
                        .parameterName("value")
                        .dataType(parameterType)
                        .parameterMode(parameterMode)
                        .build()))
                .build();
    }
}
