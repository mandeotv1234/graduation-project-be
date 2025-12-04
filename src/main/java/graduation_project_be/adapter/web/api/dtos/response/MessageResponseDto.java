package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.codes.Code;
import graduation_project_be.application.usecases.response.MessageResponse;

import java.time.LocalDateTime;

public record MessageResponseDto(
        Meta meta,
        String code,
        String message

) {
    record Meta (LocalDateTime timestamp) {}

    public static MessageResponseDto of(String message) {
        return new MessageResponseDto( new MessageResponseDto.Meta(LocalDateTime.now()), Code.OK.toString(), message);
    }
}


