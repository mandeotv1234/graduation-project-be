package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.MoodleSqlImportPreviewResponse;

import java.util.List;

public record MoodleSqlImportPreviewResponseDto(
        Long examId,
        String examTitle,
        int totalFiles,
        int validFiles,
        int invalidFiles,
        int totalQuestions,
        boolean readyToImport,
        List<FilePreviewDto> files) {

    public static MoodleSqlImportPreviewResponseDto fromResponse(MoodleSqlImportPreviewResponse response) {
        return new MoodleSqlImportPreviewResponseDto(
                response.examId(),
                response.examTitle(),
                response.totalFiles(),
                response.validFiles(),
                response.invalidFiles(),
                response.totalQuestions(),
                response.readyToImport(),
                response.files().stream().map(FilePreviewDto::fromResponse).toList());
    }

    public record FilePreviewDto(
            String fileName,
            String detectedIdentifier,
            Long studentId,
            String studentEmail,
            String studentName,
            boolean valid,
            int answeredQuestions,
            int missingQuestions,
            List<AnswerPreviewDto> answers,
            List<String> errors,
            List<String> warnings) {

        private static FilePreviewDto fromResponse(MoodleSqlImportPreviewResponse.FilePreview file) {
            return new FilePreviewDto(
                    file.fileName(),
                    file.detectedIdentifier(),
                    file.studentId(),
                    file.studentEmail(),
                    file.studentName(),
                    file.valid(),
                    file.answeredQuestions(),
                    file.missingQuestions(),
                    file.answers().stream().map(AnswerPreviewDto::fromResponse).toList(),
                    file.errors(),
                    file.warnings());
        }
    }

    public record AnswerPreviewDto(
            Long questionId,
            Integer orderIndex,
            String questionType,
            boolean hasAnswer,
            int sqlLength,
            String marker) {

        private static AnswerPreviewDto fromResponse(MoodleSqlImportPreviewResponse.AnswerPreview answer) {
            return new AnswerPreviewDto(
                    answer.questionId(),
                    answer.orderIndex(),
                    answer.questionType(),
                    answer.hasAnswer(),
                    answer.sqlLength(),
                    answer.marker());
        }
    }
}
