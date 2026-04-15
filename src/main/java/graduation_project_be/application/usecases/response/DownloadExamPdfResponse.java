package graduation_project_be.application.usecases.response;

public record DownloadExamPdfResponse(
        byte[] content,
        String fileName) {
}
