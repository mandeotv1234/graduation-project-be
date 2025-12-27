package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.CreateExamResponse;
import java.time.LocalDateTime;

public record CreateExamResponseDto(
        Long id,
        Long templateId,
        Long classId,
        Long creatorId,
        String examMatrix,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished) {
    public static CreateExamResponseDto fromResponse(CreateExamResponse response) {
        return new CreateExamResponseDto(
                response.id(),
                response.templateId(),
                response.classId(),
                response.creatorId(),
                response.examMatrix(),
                response.durationMinutes(),
                response.startTime(),
                response.endTime(),
                response.isPublished());
    }
}
