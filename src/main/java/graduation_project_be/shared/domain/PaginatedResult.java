package graduation_project_be.shared.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaginatedResult<T> {
    private List<T> data;
    private PaginationMeta pagination;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PaginationMeta {
        private int page;
        private int size;
        private long total;
        private int totalPages;
    }

    public static <T> PaginatedResult<T> of(List<T> data, int page, int size, long total) {
        int totalPages = (int) Math.ceil((double) total / size);

        return PaginatedResult.<T>builder()
                .data(data)
                .pagination(PaginationMeta.builder()
                        .page(page)
                        .size(size)
                        .total(total)
                        .totalPages(totalPages)
                        .build())
                .build();
    }
}
