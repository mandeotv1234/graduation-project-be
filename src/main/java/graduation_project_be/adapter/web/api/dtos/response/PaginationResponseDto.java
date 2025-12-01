package graduation_project_be.adapter.web.api.dtos.response;

import java.util.List;

public record PaginationResponseDto<T>(List<T> data, MetaResponseDto meta, String code, String message) {

    public static <T> PaginationResponseDto<T> valueOf(List<T> data, PaginationMetaDto pagination) {
        return new PaginationResponseDto<>(data, MetaResponseDto.of(pagination), "200", "OK");
    }

    public static <T> PaginationResponseDto<T> of(List<T> data, PaginationMetaDto pagination, String code, String message) {
        return new PaginationResponseDto<>(data, MetaResponseDto.of(pagination), code, message);
    }

    public List<T> getData() {
        return data;
    }

    public MetaResponseDto getMeta() {
        return meta;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
