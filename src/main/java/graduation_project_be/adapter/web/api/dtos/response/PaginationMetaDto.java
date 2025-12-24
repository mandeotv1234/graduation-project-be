package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.PaginationResponse;

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

    public static PaginationMetaDto fromResponse(PaginationResponse.PaginationMeta paginationMeta) {
        // FE mong muốn page bắt đầu từ 1, backend từ 0
        return new PaginationMetaDto(
                paginationMeta.getPage() + 1,
                paginationMeta.getSize(),
                paginationMeta.getTotal());
    }
}
