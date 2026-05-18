package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.ExportExamPdfRequest;
import jakarta.validation.constraints.Size;

public record ExportExamPdfRequestDto(
        @Size(max = 5000, message = "Regulations override must not exceed 5000 characters")
        String regulationsOverride
) {
    public ExportExamPdfRequest toRequest(Long examId) {
        return new ExportExamPdfRequest(examId, regulationsOverride);
    }
}
