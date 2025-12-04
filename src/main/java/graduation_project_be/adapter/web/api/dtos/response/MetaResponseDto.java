package graduation_project_be.adapter.web.api.dtos.response;

import java.time.LocalDateTime;

public record MetaResponseDto(
        LocalDateTime timestamp,
        PaginationMetaDto pagination
) {

    public static MetaResponseDto of() {
        return new MetaResponseDto(LocalDateTime.now(), null);
    }

    public static MetaResponseDto of(PaginationMetaDto pagination) {
        return new MetaResponseDto(LocalDateTime.now(), pagination);
    }
}
