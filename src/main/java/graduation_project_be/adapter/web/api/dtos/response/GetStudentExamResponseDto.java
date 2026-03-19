package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetStudentExamResponse;
import java.time.LocalDateTime;

public record GetStudentExamResponseDto(
        Long examId,
        Long classId,
        String title,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime serverTime,
        String status,
        long secondsUntilStart,
        String description,
        Integer maxAttempts,
        Integer lateThreshold,
        ExamSettingsResponseDto settings) {
    public static GetStudentExamResponseDto fromResponse(GetStudentExamResponse response) {
        return new GetStudentExamResponseDto(
                response.examId(),
                response.classId(),
                response.title(),
                response.durationMinutes(),
                response.startTime(),
                response.endTime(),
                response.serverTime(),
                response.status(),
                response.secondsUntilStart(),
                response.description(),
                response.maxAttempts(),
                response.lateThreshold(),
                ExamSettingsResponseDto.fromModel(response.settings()));
    }
}
