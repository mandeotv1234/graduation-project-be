package graduation_project_be.application.usecases.request;

import graduation_project_be.domain.models.PaginationParams;
import graduation_project_be.domain.models.enums.SortDirection;
import graduation_project_be.domain.models.enums.SortField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetClassesRequest {
    private int page;
    private int size;
    private String sortBy;
    private String sortOrder;

    public PaginationParams getPaginationParams() {
        return PaginationParams.of(
                page,
                size,
                SortField.fromString(sortBy),
                SortDirection.fromString(sortOrder));
    }
}
