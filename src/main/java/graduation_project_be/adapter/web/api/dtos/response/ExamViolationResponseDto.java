package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.ExamViolationResponse;

import java.time.LocalDateTime;

public record ExamViolationResponseDto(
        Long id,
        Long examId,
        Long studentId,
        Integer attemptNumber,
        String violationType,
        String description,
        String ipAddress,
        String userAgent,
        LocalDateTime createdAt) {

    public static ExamViolationResponseDto fromResponse(ExamViolationResponse r) {
        return new ExamViolationResponseDto(
            r.id(), r.examId(), r.studentId(), r.attemptNumber(), r.violationType(),
                r.description(), r.ipAddress(), r.userAgent(), r.createdAt());
    }
}
