package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.UnreadCountResponse;

public record UnreadCountResponseDto(long unreadCount) {

    public static UnreadCountResponseDto fromResponse(UnreadCountResponse r) {
        return new UnreadCountResponseDto(r.unreadCount());
    }
}
