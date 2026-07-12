package graduation_project_be.application.usecases.response;

import java.util.List;

public record PrepareExamSchemasResponse(
        Long examId,
        int enrolledCount,
        int preparedCount,
        int skippedReadyCount,
        int skippedActiveSessionCount,
        int skippedMaxAttemptsCount,
        int failedCount,
        boolean forceRebuild,
        boolean templateLoaded,
        List<StudentSchemaPreparationResult> results) {

    public enum PreparationStatus {
        PREPARED,
        SKIPPED_READY,
        SKIPPED_ACTIVE_SESSION,
        SKIPPED_MAX_ATTEMPTS,
        FAILED
    }

    public record StudentSchemaPreparationResult(
            Long studentId,
            Integer attemptNumber,
            String schemaName,
            PreparationStatus status,
            String message) {
    }
}
