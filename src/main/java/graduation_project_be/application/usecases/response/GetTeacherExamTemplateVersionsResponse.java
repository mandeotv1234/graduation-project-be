package graduation_project_be.application.usecases.response;

import java.util.List;

public record GetTeacherExamTemplateVersionsResponse(
        boolean canManage,
        List<GetTeacherExamTemplateVersionItemResponse> versions
) {
}
