package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.CreateExamRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

public record CreateExamRequestDto(
        @NotNull(message = "Template ID is required") Long templateId,

        @NotNull(message = "Class ID is required") Long classId,

        @NotBlank(message = "Title is required") String title,

        @NotNull(message = "Duration is required") @Positive(message = "Duration must be positive") Integer durationMinutes,

        LocalDateTime startTime,

        LocalDateTime endTime,

        Boolean isPublished) {
    public CreateExamRequest toRequest() {
        return new CreateExamRequest(
                templateId,
                classId,
                title,
                durationMinutes,
                startTime,
                endTime,
                isPublished);
    }
}
