package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.MoodleSqlImportConfirmResponse;

import java.util.List;

public record MoodleSqlImportConfirmResponseDto(
        Long examId,
        int importedCount,
        int queuedCount,
        List<ImportedFileDto> files,
        String message) {

    public static MoodleSqlImportConfirmResponseDto fromResponse(MoodleSqlImportConfirmResponse response) {
        return new MoodleSqlImportConfirmResponseDto(
                response.examId(),
                response.importedCount(),
                response.queuedCount(),
                response.files().stream().map(ImportedFileDto::fromResponse).toList(),
                response.message());
    }

    public record ImportedFileDto(
            String fileName,
            Long studentId,
            String studentEmail,
            String studentName,
            Long resultId,
            int attemptNumber,
            int answeredQuestions) {

        private static ImportedFileDto fromResponse(MoodleSqlImportConfirmResponse.ImportedFile file) {
            return new ImportedFileDto(
                    file.fileName(),
                    file.studentId(),
                    file.studentEmail(),
                    file.studentName(),
                    file.resultId(),
                    file.attemptNumber(),
                    file.answeredQuestions());
        }
    }
}
