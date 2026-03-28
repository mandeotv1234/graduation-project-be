package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.UpdateTeacherExamSettingsRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;

public record UpdateTeacherExamSettingsRequestDto(
        @NotBlank(message = "Title is required") String title,
        @NotNull(message = "Duration is required") @Positive(message = "Duration must be positive") Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        String description,
        @Positive(message = "Max attempts must be positive") Integer maxAttempts,
        @PositiveOrZero(message = "Late threshold must be positive or zero") Integer lateThreshold,
        ExamSettingsDto settings
) {
    public UpdateTeacherExamSettingsRequest toRequest() {
        return new UpdateTeacherExamSettingsRequest(
                title,
                durationMinutes,
                startTime,
                endTime,
                isPublished,
                description,
                maxAttempts,
                lateThreshold,
                settings != null ? settings.toModel() : null
        );
    }
}
