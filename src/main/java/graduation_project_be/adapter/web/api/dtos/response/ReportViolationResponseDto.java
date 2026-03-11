package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.ReportViolationResponse;

import java.time.LocalDateTime;

public record ReportViolationResponseDto(
        Long violationId,
        Long examId,
        Long studentId,
        String violationType,
        String description,
        long violationCount,
        boolean autoSubmitted,
        String message,
        LocalDateTime createdAt) {

    public static ReportViolationResponseDto fromResponse(ReportViolationResponse r) {
        return new ReportViolationResponseDto(
                r.violationId(), r.examId(), r.studentId(),
                r.violationType(), r.description(),
                r.violationCount(), r.autoSubmitted(),
                r.message(), r.createdAt());
    }
}
