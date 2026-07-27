package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.UpdateClassRequest;
import graduation_project_be.application.usecases.support.ClassInputValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.Optional;

@Builder
public record UpdateClassRequestDto(
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

    List<@Valid StudentInfo> students
) {
    @Builder
    public record StudentInfo(
        @NotBlank(message = "Student ID is required")
        @Pattern(
            regexp = ClassInputValidator.STUDENT_CODE_REGEX,
            message = "Student ID must contain exactly 8 digits"
        )
        String studentId,

        String fullName
    ) {}

    public UpdateClassRequest toRequest(Long classId) {
        return UpdateClassRequest.builder()
                .classId(classId)
                .classCode(classCode)
                .semester(semester)
                .students(Optional.ofNullable(students).orElse(List.of()).stream()
                        .map(s -> UpdateClassRequest.StudentInfo.builder()
                                .studentId(s.studentId())
                                .fullName(s.fullName())
                                .build())
                        .toList())
                .build();
    }
}
