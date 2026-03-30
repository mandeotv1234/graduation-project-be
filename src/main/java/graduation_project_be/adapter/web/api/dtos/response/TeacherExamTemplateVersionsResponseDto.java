package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.GetTeacherExamTemplateVersionsUsecase;

import java.util.List;

public record TeacherExamTemplateVersionsResponseDto(
        boolean canManage,
        List<TeacherExamTemplateVersionResponseDto> versions
) {
    public static TeacherExamTemplateVersionsResponseDto fromResponse(
            GetTeacherExamTemplateVersionsUsecase.TeacherExamTemplateVersionsResult response) {
        return new TeacherExamTemplateVersionsResponseDto(
                response.canManage(),
                response.versions().stream()
                        .map(TeacherExamTemplateVersionResponseDto::fromResponse)
                        .toList()
        );
    }
}
