package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateClassRequest;
import graduation_project_be.application.usecases.support.ClassInputValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Builder
public record CreateClassRequestDto(
    @NotBlank(message = "Class code is required")
    @Size(
        max = ClassInputValidator.CLASS_CODE_MAX_LENGTH,
        message = "Class code must not exceed 20 characters"
    )
    String classCode,

    @NotBlank(message = "Semester is required")
    @Size(
        max = ClassInputValidator.SEMESTER_MAX_LENGTH,
        message = "Semester must not exceed 20 characters"
    )
    String semester,

    List<@Valid StudentInfoDto> students
) {
    @Builder
    public record StudentInfoDto(
        @NotBlank(message = "Student ID is required")
        @Pattern(
            regexp = ClassInputValidator.STUDENT_CODE_REGEX,
            message = "Student ID must contain exactly 8 digits"
        )
        String studentId,

        String fullName
    ) {}

    public CreateClassRequest toRequest() {
        return CreateClassRequest.builder()
                .classCode(this.classCode)
                .semester(this.semester)
                .students(Optional.ofNullable(this.students).orElse(List.of()).stream()
                        .map(s -> CreateClassRequest.StudentInfo.builder()
                                .studentId(s.studentId())
                                .fullName(s.fullName())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }
}
