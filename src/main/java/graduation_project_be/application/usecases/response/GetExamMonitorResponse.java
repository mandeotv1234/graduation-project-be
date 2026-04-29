package graduation_project_be.application.usecases.response;

import java.time.LocalDateTime;
import java.util.List;

public record GetExamMonitorResponse(
        Long examId,
        String examTitle,
        Long classId,
        String classCode,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        int totalStudents,
        int totalViolators,
        int totalHighRisk,
        int totalFilteredStudents,
        List<GetExamMonitorStudentResponse> students) {
}
