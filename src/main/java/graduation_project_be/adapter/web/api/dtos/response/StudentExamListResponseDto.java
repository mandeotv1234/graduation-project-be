package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.StudentExamListResponse;
import java.time.LocalDateTime;

public record StudentExamListResponseDto(
        Long examId,
        String title,
        Long classId,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        boolean banned) {
    public static StudentExamListResponseDto fromResponse(StudentExamListResponse r) {
        return new StudentExamListResponseDto(
                r.examId(), r.title(), r.classId(),
                r.durationMinutes(), r.startTime(), r.endTime(), r.banned());
    }
}
