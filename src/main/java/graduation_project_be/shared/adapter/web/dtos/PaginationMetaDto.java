package graduation_project_be.shared.adapter.web.dtos;

import graduation_project_be.shared.domain.PaginatedResult;

public record PaginationMetaDto(int page, int size, long total) {

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotal() {
        return total;
    }

    public int getTotalPages() {
        return (int) Math.ceil((double) total / size);
    }

    public static PaginationMetaDto from(PaginatedResult.PaginationMeta paginationMeta) {
        return new PaginationMetaDto(
                paginationMeta.getPage(),
                paginationMeta.getSize(),
                paginationMeta.getTotal()
        );
    }
}
