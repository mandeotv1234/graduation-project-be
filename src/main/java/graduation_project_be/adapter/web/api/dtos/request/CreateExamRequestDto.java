package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateExamRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.time.LocalDateTime;

public record CreateExamRequestDto(
        Long specificationId,

        @NotNull(message = "Class ID is required") @Positive(message = "Class ID must be positive") Long classId,

        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        @NotNull(message = "Duration is required") @Positive(message = "Duration must be positive") @Max(value = 240, message = "Duration must not exceed 240 minutes") Integer durationMinutes,

        LocalDateTime startTime,

        LocalDateTime endTime,

        Boolean isPublished,

        String description,

        @Positive(message = "Max attempts must be positive")
        @Max(value = 99, message = "Max attempts must not exceed 99")
        Integer maxAttempts,

        @PositiveOrZero(message = "Late threshold must be positive or zero")
        @Max(value = 240, message = "Late threshold must not exceed 240 minutes")
        Integer lateThreshold,

        @Valid ExamSettingsDto settings) {
    public CreateExamRequest toRequest() {
        return toRequest(null, null);
    }

    public CreateExamRequest toRequest(String pdfFilePath, String originalPdfFileName) {
        return new CreateExamRequest(
                specificationId,
                classId,
                title,
                durationMinutes,
                startTime,
                endTime,
                isPublished,
                description,
                maxAttempts,
                lateThreshold,
                settings != null ? settings.toModel() : null,
                pdfFilePath,
                originalPdfFileName);
    }
}
