package graduation_project_be.application.port.services;

import graduation_project_be.domain.models.TeacherNotification;

import java.util.List;

/**
 * Buffer service for notification persistence.
 * Notifications are buffered in Redis and flushed to DB
 * when the buffer reaches a configured threshold.
 */
public interface NotificationBufferService {

    /**
     * Add a notification to the buffer.
     * If the buffer reaches the threshold, flush all buffered notifications.
     *
     * @param notification the notification to buffer
     */
    void buffer(TeacherNotification notification);

    /**
     * Force flush all buffered notifications to the database.
     *
     * @return the list of flushed notifications
     */
    List<TeacherNotification> flush();

    /**
     * Get the current buffer size.
     *
     * @return number of notifications in the buffer
     */
    long getBufferSize();
}
