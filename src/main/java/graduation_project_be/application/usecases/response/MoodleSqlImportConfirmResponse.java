package graduation_project_be.application.usecases.response;

import java.util.List;

public record MoodleSqlImportConfirmResponse(
        Long examId,
        int importedCount,
        int queuedCount,
        List<ImportedFile> files,
        String message) {

    public record ImportedFile(
            String fileName,
            Long studentId,
            String studentEmail,
            String studentName,
            Long resultId,
            int attemptNumber,
            int answeredQuestions) {
    }
}
