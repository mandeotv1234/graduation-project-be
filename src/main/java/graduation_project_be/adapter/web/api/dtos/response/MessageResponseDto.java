package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.MessageResponse;

public record MessageResponseDto(String message) {
    public static MessageResponseDto from(MessageResponse out){
        return new MessageResponseDto(out.message());
    }
}
