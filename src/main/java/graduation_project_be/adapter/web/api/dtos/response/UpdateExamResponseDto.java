package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.UpdateExamResponse;
import java.time.LocalDateTime;

public record UpdateExamResponseDto(
        Long id,
        String title,
        Long specificationId,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        String description,
        Integer maxAttempts,
        Integer lateThreshold,
        ExamSettingsResponseDto settings) {

    public static UpdateExamResponseDto fromResponse(UpdateExamResponse response) {
        return new UpdateExamResponseDto(
                response.id(),
                response.title(),
                response.specificationId(),
                response.durationMinutes(),
                response.startTime(),
                response.endTime(),
                response.isPublished(),
                response.description(),
                response.maxAttempts(),
                response.lateThreshold(),
                response.settings() != null ? ExamSettingsResponseDto.fromModel(response.settings()) : null);
    }
}
