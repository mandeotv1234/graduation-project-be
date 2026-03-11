package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.ExamViolation;

import java.time.LocalDateTime;

public record ExamViolationResponse(
        Long id,
        Long examId,
        Long studentId,
        String violationType,
        String description,
        String ipAddress,
        String userAgent,
        LocalDateTime createdAt) {

    public static ExamViolationResponse fromModel(ExamViolation model) {
        return new ExamViolationResponse(
                model.getId(),
                model.getExamId(),
                model.getStudentId(),
                model.getViolationType(),
                model.getDescription(),
                model.getIpAddress(),
                model.getUserAgent(),
                model.getCreatedAt());
    }
}
