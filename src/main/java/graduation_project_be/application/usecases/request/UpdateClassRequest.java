package graduation_project_be.application.usecases.request;

import lombok.Builder;
import java.util.List;

@Builder
public record UpdateClassRequest(
    Long classId,
    String classCode,
    String semester,
    List<StudentInfo> students
) {
    @Builder
    public record StudentInfo(
        String studentId,
        String fullName
    ) {}
}
