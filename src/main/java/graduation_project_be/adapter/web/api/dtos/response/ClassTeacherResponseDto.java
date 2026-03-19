package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetClassTeachersResponse;

import java.time.LocalDateTime;

public record ClassTeacherResponseDto(
        Long id,
        String email,
        String fullName,
        LocalDateTime addedAt,
        boolean isCreator) {

    public static ClassTeacherResponseDto fromResponse(GetClassTeachersResponse response) {
        return new ClassTeacherResponseDto(
                response.id(),
                response.email(),
                response.fullName(),
                response.addedAt(),
                response.isCreator());
    }
}
