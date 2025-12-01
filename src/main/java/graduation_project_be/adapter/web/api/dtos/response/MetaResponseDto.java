
package graduation_project_be.adapter.web.api.dtos.response;

import java.util.Date;

public record MetaResponseDto(Date timestamp, PaginationMetaDto pagination) {

    public static MetaResponseDto of() {
        return new MetaResponseDto(new Date(), null);
    }

    public static MetaResponseDto of(PaginationMetaDto pagination) {
        return new MetaResponseDto(new Date(), pagination);
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public PaginationMetaDto getPagination() {
        return pagination;
    }
}
