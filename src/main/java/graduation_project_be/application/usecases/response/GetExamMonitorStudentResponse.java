package graduation_project_be.application.usecases.response;

import java.time.LocalDateTime;

public record GetExamMonitorStudentResponse(
        Long studentId,
        String studentEmail,
        String studentName,
        int violationCount,
        String latestViolationType,
        String latestViolationDescription,
        LocalDateTime latestViolationAt,
        boolean autoSubmitted,
        String status) {
}
