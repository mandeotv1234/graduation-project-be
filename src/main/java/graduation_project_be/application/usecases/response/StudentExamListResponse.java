package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Exam;
import java.time.LocalDateTime;

public record StudentExamListResponse(
        Long examId,
        String title,
        Long classId,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        boolean banned) {
    public static StudentExamListResponse fromModel(Exam exam, boolean banned) {
        return new StudentExamListResponse(
                exam.getId(), exam.getTitle(), exam.getClassId(),
                exam.getDurationMinutes(), exam.getStartTime(), exam.getEndTime(), banned);
    }
}
