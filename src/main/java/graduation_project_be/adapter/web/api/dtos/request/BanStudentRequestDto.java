package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.BanStudentRequest;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BanStudentRequestDto(
        @NotNull(message = "studentId is required")
        Long studentId,
        @Size(max = 200, message = "reason must not exceed 200 characters")
        String reason
) {
    public BanStudentRequest toRequest(Long classId) {
        return new BanStudentRequest(classId, studentId, reason);
    }
}
