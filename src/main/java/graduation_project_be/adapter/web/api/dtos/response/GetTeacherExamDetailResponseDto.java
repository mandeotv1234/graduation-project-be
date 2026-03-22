package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetTeacherExamDetailResponse;
import java.time.LocalDateTime;

public record GetTeacherExamDetailResponseDto(
        Long id,
        Long specificationId,
        Long classId,
        String title,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        String description,
        Integer maxAttempts,
        Integer lateThreshold,
        ExamSettingsResponseDto settings) {

    public static GetTeacherExamDetailResponseDto fromResponse(GetTeacherExamDetailResponse response) {
        return new GetTeacherExamDetailResponseDto(
                response.id(),
                response.specificationId(),
                response.classId(),
                response.title(),
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
