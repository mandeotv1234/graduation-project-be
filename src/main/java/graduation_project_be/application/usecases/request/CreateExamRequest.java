package graduation_project_be.application.usecases.request;

import java.time.LocalDateTime;

public record CreateExamRequest(
        Long templateId,
        Long classId,
        String examMatrix,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished) {
}
