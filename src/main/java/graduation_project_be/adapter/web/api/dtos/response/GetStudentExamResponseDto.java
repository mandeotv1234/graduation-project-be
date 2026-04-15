package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetStudentExamResponse;
import graduation_project_be.domain.models.TableMetadata;
import java.time.LocalDateTime;
import java.util.List;

public record GetStudentExamResponseDto(
        Long examId,
        Long classId,
        String className,
        String title,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime serverTime,
        String status,
        long secondsUntilStart,
        String description,
        Integer maxAttempts,
        Long usedAttempts,
        Integer lateThreshold,
        ExamSettingsResponseDto settings,
        List<TableMetadata> schema,
        String pdfFilePath,
        String originalPdfFileName) {
    public static GetStudentExamResponseDto fromResponse(GetStudentExamResponse response) {
        return new GetStudentExamResponseDto(
                response.examId(),
                response.classId(),
                response.className(),
                response.title(),
                response.durationMinutes(),
                response.startTime(),
                response.endTime(),
                response.serverTime(),
                response.status(),
                response.secondsUntilStart(),
                response.description(),
                response.maxAttempts(),
                response.usedAttempts(),
                response.lateThreshold(),
                ExamSettingsResponseDto.fromModel(response.settings()),
                response.schema(),
                response.pdfFilePath(),
                response.originalPdfFileName());
    }
}
