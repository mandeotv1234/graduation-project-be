package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateClassRequest;
import lombok.Builder;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Builder
public record CreateClassRequestDto(
    String classCode,
    String semester,
    List<StudentInfoDto> students
) {
    @Builder
    public record StudentInfoDto(
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
