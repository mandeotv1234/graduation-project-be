package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.DeleteNotificationResponse;

public record DeleteNotificationResponseDto(
        int deletedCount,
        String message) {

    public static DeleteNotificationResponseDto fromResponse(DeleteNotificationResponse r) {
        return new DeleteNotificationResponseDto(r.deletedCount(), r.message());
    }
}
