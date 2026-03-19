package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.CreateExamResponse;

import java.time.LocalDateTime;

public record CreateExamResponseDto(
        Long id,
        Long specificationId,
        Long classId,
        Long creatorId,
        String title,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        LocalDateTime createdAt,
        String description,
        Integer maxAttempts,
        Integer lateThreshold,
        ExamSettingsResponseDto settings) {
    public static CreateExamResponseDto fromResponse(CreateExamResponse response) {
        return new CreateExamResponseDto(
                response.id(),
                response.specificationId(),
                response.classId(),
                response.creatorId(),
                response.title(),
                response.durationMinutes(),
                response.startTime(),
                response.endTime(),
                response.isPublished(),
                response.createdAt(),
                response.description(),
                response.maxAttempts(),
                response.lateThreshold(),
                ExamSettingsResponseDto.fromModel(response.settings()));
    }
}

