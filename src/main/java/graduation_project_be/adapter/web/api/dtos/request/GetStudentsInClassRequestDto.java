package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetStudentsInClassRequest;
import lombok.Builder;

@Builder
public record GetStudentsInClassRequestDto(
        Long classId,
        int page,
        int size,
        String sortBy,
        String sortOrder
) {
    public GetStudentsInClassRequest toRequest() {
        return GetStudentsInClassRequest.builder()
                .classId(classId)
                .page(page)
                .size(size)
                .sortBy(sortBy != null ? sortBy : "CREATED_AT")
                .sortOrder(sortOrder != null ? sortOrder : "DESC")
                .build();
    }
}
