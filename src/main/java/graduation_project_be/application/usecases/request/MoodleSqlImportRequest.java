package graduation_project_be.application.usecases.request;

import java.util.List;

public record MoodleSqlImportRequest(
        Long examId,
        List<UploadedSqlFile> files) {

    public record UploadedSqlFile(
            String fileName,
            String content,
            long sizeBytes,
            String contentType) {
    }
}
