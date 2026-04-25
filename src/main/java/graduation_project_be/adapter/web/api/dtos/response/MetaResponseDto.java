package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.shared.utils.TimeUtils;
import java.time.LocalDateTime;

public record MetaResponseDto(
        LocalDateTime timestamp,
        PaginationMetaDto pagination
) {

    public static MetaResponseDto of() {
        return new MetaResponseDto(TimeUtils.now(), null);
    }

    public static MetaResponseDto of(PaginationMetaDto pagination) {
        return new MetaResponseDto(TimeUtils.now(), pagination);
    }
}
