package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.SubmitExamResponse;
import graduation_project_be.domain.models.enums.GradingStatus;

import java.time.LocalDateTime;

public record SubmitExamResponseDto(
        Long examId,
        Long studentId,
        LocalDateTime submittedAt,
        GradingStatus status) {

    public static SubmitExamResponseDto fromResponse(SubmitExamResponse r) {
        return new SubmitExamResponseDto(
                r.examId(), r.studentId(), r.submittedAt(), r.status());
    }
}
