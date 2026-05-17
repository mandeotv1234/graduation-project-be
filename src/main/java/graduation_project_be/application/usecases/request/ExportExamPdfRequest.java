package graduation_project_be.application.usecases.request;

public record ExportExamPdfRequest(
        Long examId,
        /** Optional caller-provided regulations text; null means use default from exam. */
        String regulationsOverride
) {}
