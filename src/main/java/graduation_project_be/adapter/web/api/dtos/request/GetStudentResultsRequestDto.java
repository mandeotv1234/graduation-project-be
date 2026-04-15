package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetStudentResultsRequest;
import lombok.Builder;

@Builder
public record GetStudentResultsRequestDto(
        int page,
        int size,
        String sortBy,
        String sortOrder) {
    public GetStudentResultsRequest toRequest() {
        return GetStudentResultsRequest.builder()
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .build();
    }
}
