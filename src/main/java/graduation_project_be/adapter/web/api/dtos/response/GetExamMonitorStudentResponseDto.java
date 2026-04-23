package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetExamMonitorStudentResponse;

import java.time.LocalDateTime;

public record GetExamMonitorStudentResponseDto(
        Long studentId,
        String studentEmail,
        String studentName,
        int violationCount,
        String latestViolationType,
        String latestViolationDescription,
        LocalDateTime latestViolationAt,
        boolean autoSubmitted,
        String status,
        String examStatus) {

    public static GetExamMonitorStudentResponseDto fromResponse(GetExamMonitorStudentResponse response) {
        return new GetExamMonitorStudentResponseDto(
                response.studentId(),
                response.studentEmail(),
                response.studentName(),
                response.violationCount(),
                response.latestViolationType(),
                response.latestViolationDescription(),
                response.latestViolationAt(),
                response.autoSubmitted(),
                response.status(),
                response.examStatus());
    }
}
