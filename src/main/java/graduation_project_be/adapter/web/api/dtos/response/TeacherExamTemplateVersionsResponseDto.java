package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetTeacherExamTemplateVersionsResponse;

import java.util.List;

public record TeacherExamTemplateVersionsResponseDto(
        boolean canManage,
        List<TeacherExamTemplateVersionResponseDto> versions
) {
    public static TeacherExamTemplateVersionsResponseDto fromResponse(
            GetTeacherExamTemplateVersionsResponse response) {
        return new TeacherExamTemplateVersionsResponseDto(
                response.canManage(),
                response.versions().stream()
                        .map(TeacherExamTemplateVersionResponseDto::fromResponse)
                        .toList()
        );
    }
}
