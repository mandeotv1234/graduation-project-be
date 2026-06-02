package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.BanStudentRequest;
import jakarta.validation.constraints.NotNull;

public record BanStudentRequestDto(
        @NotNull(message = "studentId is required")
        Long studentId,
        String reason
) {
    public BanStudentRequest toRequest(Long classId) {
        return new BanStudentRequest(classId, studentId, reason);
    }
}
