package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.ReportViolationRequest;
import jakarta.validation.constraints.NotBlank;

public record ReportViolationRequestDto(
        @NotBlank(message = "Violation type is required") String violationType,
        String description) {

    public ReportViolationRequest toRequest(Long examId, String ipAddress, String userAgent) {
        return new ReportViolationRequest(examId, violationType, description, ipAddress, userAgent);
    }
}
