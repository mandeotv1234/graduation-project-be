package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.UpdateExamRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

public record UpdateExamRequestDto(
        String title,
        @Positive(message = "Duration must be positive") Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        String description,
        @Positive(message = "Max attempts must be positive") Integer maxAttempts,
        Integer lateThreshold,
        @Valid ExamSettingsDto settings) {

    public UpdateExamRequest toRequest(Long examId) {
        return new UpdateExamRequest(
                examId,
                title,
                durationMinutes,
                startTime,
                endTime,
                isPublished,
                description,
                maxAttempts,
                lateThreshold,
                settings != null ? settings.toModel() : null);
    }
}
