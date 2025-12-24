package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.PaginationResponse;
import java.util.List;
import java.util.function.Function;

public record PaginationResponseDto<T>(List<T> data, MetaResponseDto meta, String code, String message) {

    public static <T> PaginationResponseDto<T> valueOf(List<T> data, PaginationMetaDto pagination) {
        return new PaginationResponseDto<>(data, MetaResponseDto.of(pagination), "200", "OK");
    }

    public static <T> PaginationResponseDto<T> of(List<T> data, PaginationMetaDto pagination, String code,
            String message) {
        return new PaginationResponseDto<>(data, MetaResponseDto.of(pagination), code, message);
    }

    public static <U, T> PaginationResponseDto<T> fromResponse(PaginationResponse<U> usecaseResponse,
            Function<U, T> mapper, String code, String message) {
        List<T> data = usecaseResponse.data().stream()
                .map(mapper)
                .toList();

        PaginationMetaDto paginationMeta = PaginationMetaDto.fromResponse(usecaseResponse.pagination());

        return new PaginationResponseDto<>(data, MetaResponseDto.of(paginationMeta), code, message);
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
