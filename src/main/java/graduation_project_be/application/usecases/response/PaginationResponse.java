package graduation_project_be.application.usecases.response;

import lombok.Getter;

import java.util.List;

public record PaginationResponse<T>(List<T> data,
                                    PaginationMeta pagination) {

    public static <T> PaginationResponse<T> valueOf(List<T> data, PaginationMeta pagination) {
        return new PaginationResponse<>(data, pagination);
    }

    @Getter
    public static class PaginationMeta {
        private final int page;
        private final int size;
        private final long total;
        private final int totalPages;

        private PaginationMeta(int page, int size, long total) {
            this.page = page ;
            this.size = size;
            this.total = total;
            this.totalPages = (int) Math.ceil((double) total / size);
        }

        public static PaginationMeta valueOf(int page, int size, long total) {
            return new PaginationMeta(page, size, total);
        }
    }
}
