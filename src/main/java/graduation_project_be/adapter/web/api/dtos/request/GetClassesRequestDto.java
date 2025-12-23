package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetClassesRequest;
import lombok.Data;

@Data
public class GetClassesRequestDto {
    private int page = 0;
    private int size = 10;
    private String sortBy = "TIME";
    private String sortOrder = "DESC";

    public GetClassesRequest toRequest() {
        return GetClassesRequest.builder()
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .build();
    }
}