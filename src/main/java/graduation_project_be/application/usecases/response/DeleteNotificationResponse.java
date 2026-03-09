package graduation_project_be.application.usecases.response;

public record DeleteNotificationResponse(
        int deletedCount,
        String message) {

    public static DeleteNotificationResponse ofOne() {
        return new DeleteNotificationResponse(1, "Notification deleted successfully");
    }

    public static DeleteNotificationResponse ofAll(int count) {
        return new DeleteNotificationResponse(count, count + " notifications deleted successfully");
    }
}
