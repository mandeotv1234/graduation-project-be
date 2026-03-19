package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.AddTeacherToClassRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record AddTeacherToClassRequestDto(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        String email) {

    public AddTeacherToClassRequest toRequest(Long classId) {
        return AddTeacherToClassRequest.builder()
                .classId(classId)
                .email(email)
                .build();
    }
}
