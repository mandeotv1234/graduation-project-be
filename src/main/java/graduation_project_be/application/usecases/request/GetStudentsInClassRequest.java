package graduation_project_be.application.usecases.request;

import graduation_project_be.domain.models.PaginationParams;
import graduation_project_be.domain.models.enums.SortDirection;
import graduation_project_be.domain.models.enums.SortField;
import lombok.Builder;

@Builder

public record GetStudentsInClassRequest(
    Long classId,
    int page,
    int size,
    String sortBy,
    String sortOrder
) {
    public PaginationParams getPaginationParams() {
        return PaginationParams.of(
            page,
            size,
            SortField.fromString(sortBy),
            SortDirection.fromString(sortOrder)
        );
    }
} 


