package graduation_project_be.application.usecases.response;

import java.util.List;

public record MoodleSqlImportPreviewResponse(
        Long examId,
        String examTitle,
        int totalFiles,
        int validFiles,
        int invalidFiles,
        int totalQuestions,
        boolean readyToImport,
        List<FilePreview> files) {

    public record FilePreview(
            String fileName,
            String detectedIdentifier,
            Long studentId,
            String studentEmail,
            String studentName,
            boolean valid,
            int answeredQuestions,
            int missingQuestions,
            List<AnswerPreview> answers,
            List<String> errors,
            List<String> warnings) {
    }

    public record AnswerPreview(
            Long questionId,
            Integer orderIndex,
            String questionType,
            boolean hasAnswer,
            int sqlLength,
            String marker) {
    }
}
