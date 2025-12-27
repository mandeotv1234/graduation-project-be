package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetStudentExamResponse;
import java.time.LocalDateTime;

public record GetStudentExamResponseDto(
        Long examId,
        Long classId,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime) {
    public static GetStudentExamResponseDto fromResponse(GetStudentExamResponse response) {
        return new GetStudentExamResponseDto(
                response.examId(),
                response.classId(),
                response.durationMinutes(),
                response.startTime(),
                response.endTime());
    }
}
