package graduation_project_be.adapter.web.api.dtos.response;

import java.util.Date;
import java.util.List;

public record PaginationResponseDto<T>(List<T> data, PaginationMetaDto pagination, Meta meta) {

    public static <T> PaginationResponseDto<T> valueOf(List<T> data, PaginationMetaDto pagination) {
        return new PaginationResponseDto<>(data, pagination, new Meta());
    }

    public List<T> getData() {
        return data;
    }

    public PaginationMetaDto getPagination() {
        return pagination;
    }

    public Meta getMeta() {
        return meta;
    }

    public static class Meta {
        private final Date timestamp;

        Meta() {
            this.timestamp = new Date();
        }

        public Date getTimestamp() {
            return timestamp;
        }
    }
}
