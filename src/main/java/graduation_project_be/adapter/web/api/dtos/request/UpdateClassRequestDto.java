package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.UpdateClassRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.List;
import java.util.Optional;

@Builder
public record UpdateClassRequestDto(
    @NotBlank(message = "Class code is required")
    String classCode,

    @NotBlank(message = "Semester is required")
    String semester,

    List<StudentInfo> students
) {
    @Builder
    public record StudentInfo(
        @NotBlank(message = "Student ID is required")
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
