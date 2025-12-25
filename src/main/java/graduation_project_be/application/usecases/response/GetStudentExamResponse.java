package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Exam;

import java.time.LocalDateTime;

public record GetStudentExamResponse(
        Long examId,
        Long classId,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime
) {
    public static GetStudentExamResponse fromModel(Exam exam) {
        return new GetStudentExamResponse(
                exam.getId(),
                exam.getClassId(),
                exam.getDurationMinutes(),
                exam.getStartTime(),
                exam.getEndTime()
        );
    }
}
