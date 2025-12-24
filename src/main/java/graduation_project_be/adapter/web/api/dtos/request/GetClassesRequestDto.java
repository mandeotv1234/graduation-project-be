package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetClassesRequest;
import lombok.Builder;

@Builder
public record GetClassesRequestDto(
        int page,
        int size,
        String sortBy,
        String sortOrder) {
    public GetClassesRequest toRequest() {
        return GetClassesRequest.builder()
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .build();
    }
}