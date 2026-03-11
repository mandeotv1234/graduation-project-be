package graduation_project_be.application.usecases.request;

public record DeleteNotificationRequest(
        Long notificationId,
        boolean deleteAll) {
}
