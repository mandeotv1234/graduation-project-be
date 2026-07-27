package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.usecases.support.ClassInputValidator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassRequestDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void rejectsValuesThatExceedDatabaseLimitsOrUseInvalidStudentCodes() {
        CreateClassRequestDto createRequest = CreateClassRequestDto.builder()
                .classCode("C".repeat(21))
                .semester("S".repeat(21))
                .students(List.of(CreateClassRequestDto.StudentInfoDto.builder()
                        .studentId("1234567")
                        .build()))
                .build();
        UpdateClassRequestDto updateRequest = UpdateClassRequestDto.builder()
                .classCode("C".repeat(21))
                .semester("S".repeat(21))
                .students(List.of(UpdateClassRequestDto.StudentInfo.builder()
                        .studentId("123456789")
                        .build()))
                .build();

        assertValidationMessages(validator.validate(createRequest));
        assertValidationMessages(validator.validate(updateRequest));
    }

    @Test
    void acceptsDatabaseLengthBoundariesAndEightDigitStudentCodes() {
        CreateClassRequestDto request = CreateClassRequestDto.builder()
                .classCode("C".repeat(20))
                .semester("S".repeat(20))
                .students(List.of(CreateClassRequestDto.StudentInfoDto.builder()
                        .studentId("22120201")
                        .build()))
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void validatesInputsAtTheApplicationBoundary() {
        assertThatCode(() -> ClassInputValidator.validateClassDetails(
                "C".repeat(20),
                "S".repeat(20)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ClassInputValidator.validateClassDetails(
                "C".repeat(21),
                "HK1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("20");
        assertThat(ClassInputValidator.isValidStudentCode("22120201")).isTrue();
        assertThat(ClassInputValidator.isValidStudentCode("2212020")).isFalse();
        assertThat(ClassInputValidator.isValidStudentCode("2212020A")).isFalse();
    }

    private void assertValidationMessages(Set<? extends ConstraintViolation<?>> violations) {
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .contains(
                        "Class code must not exceed 20 characters",
                        "Semester must not exceed 20 characters",
                        "Student ID must contain exactly 8 digits");
    }
}
