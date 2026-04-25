package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.codes.Code;
import java.time.LocalDateTime;

public record MessageResponseDto(
        Meta meta,
        String code,    
        String message

) {
    record Meta (LocalDateTime timestamp) {}

    public static MessageResponseDto of(String message) {
        return new MessageResponseDto( new MessageResponseDto.Meta(TimeUtils.now()), Code.OK.toString(), message);
    }
}


