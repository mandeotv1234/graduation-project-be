package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.ExamViolation;

import java.time.LocalDateTime;

public record ReportViolationResponse(
        Long violationId,
        Long examId,
        Long studentId,
        String violationType,
        String description,
        long violationCount,
        boolean autoSubmitted,
        String message,
        LocalDateTime createdAt) {

    public static ReportViolationResponse fromModel(ExamViolation model, long violationCount,
                                                     boolean autoSubmitted) {
        String message = autoSubmitted
                ? "Maximum violations reached. Exam has been auto-submitted."
                : "Violation recorded. Warning " + violationCount + "/3.";

        return new ReportViolationResponse(
                model.getId(), model.getExamId(), model.getStudentId(),
                model.getViolationType(), model.getDescription(),
                violationCount, autoSubmitted, message, model.getCreatedAt());
    }
}
