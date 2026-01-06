package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Exam;
import java.time.LocalDateTime;

public record CreateExamResponse(
        Long id,
        Long templateId,
        Long classId,
        Long creatorId,
        String examMatrix,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished) {
    public static CreateExamResponse fromModel(Exam exam) {
        return new CreateExamResponse(
                exam.getId(),
                exam.getTemplateId(),
                exam.getClassId(),
                exam.getCreatorId(),
                exam.getExamMatrix(),
                exam.getDurationMinutes(),
                exam.getStartTime(),
                exam.getEndTime(),
                exam.getIsPublished());
    }
}
