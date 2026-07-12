package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.PrepareExamSchemasResponse;

import java.util.List;

public record PrepareExamSchemasResponseDto(
        Long examId,
        int enrolledCount,
        int preparedCount,
        int skippedReadyCount,
        int skippedActiveSessionCount,
        int skippedMaxAttemptsCount,
        int failedCount,
        boolean forceRebuild,
        boolean templateLoaded,
        List<StudentSchemaPreparationResultDto> results) {

    public static PrepareExamSchemasResponseDto fromResponse(PrepareExamSchemasResponse response) {
        return new PrepareExamSchemasResponseDto(
                response.examId(),
                response.enrolledCount(),
                response.preparedCount(),
                response.skippedReadyCount(),
                response.skippedActiveSessionCount(),
                response.skippedMaxAttemptsCount(),
                response.failedCount(),
                response.forceRebuild(),
                response.templateLoaded(),
                response.results().stream()
                        .map(StudentSchemaPreparationResultDto::fromResponse)
                        .toList());
    }

    public record StudentSchemaPreparationResultDto(
            Long studentId,
            Integer attemptNumber,
            String schemaName,
            String status,
            String message) {

        static StudentSchemaPreparationResultDto fromResponse(
                PrepareExamSchemasResponse.StudentSchemaPreparationResult result) {
            return new StudentSchemaPreparationResultDto(
                    result.studentId(),
                    result.attemptNumber(),
                    result.schemaName(),
                    result.status().name(),
                    result.message());
        }
    }
}
