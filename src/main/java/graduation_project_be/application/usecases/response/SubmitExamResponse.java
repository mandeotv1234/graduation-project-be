package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.enums.GradingStatus;

import java.time.LocalDateTime;

public record SubmitExamResponse(
        Long examId,
        Long studentId,
        LocalDateTime submittedAt,
        GradingStatus status) {
}
