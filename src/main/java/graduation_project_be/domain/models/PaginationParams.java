package graduation_project_be.domain.models;

import graduation_project_be.domain.models.enums.SortDirection;
import graduation_project_be.domain.models.enums.SortField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaginationParams {
    private int page;
    private int size;
    private SortField sortBy;
    private SortDirection sortOrder;

    public static PaginationParams of(int page, int size) {
        return PaginationParams.builder()
                .page(page)
                .size(size)
                .build();
    }

    public static PaginationParams of(int page, int size, SortField sortBy, SortDirection sortOrder) {

        return PaginationParams.builder()
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .build();
    }

    public static PaginationParams of(int page, int size, String sortBy, String sortOrder) {
        return PaginationParams.builder()
                .page(page)
                .size(size)
                .sortBy(SortField.fromString(sortBy))
                .sortOrder(SortDirection.fromString(sortOrder))
                .build();
    }

}