package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;
import graduation_project_be.domain.models.TeacherNotification;

import java.util.List;

public interface TeacherNotificationRepository {

    List<TeacherNotification> saveAll(List<TeacherNotification> notifications);

    PaginatedResult<TeacherNotification> findByTeacherId(Long teacherId, PaginationParams params);

    long countUnreadByTeacherId(Long teacherId);

    boolean markAsRead(Long id, Long teacherId);

    int markAllAsRead(Long teacherId);

    boolean deleteByIdAndTeacherId(Long id, Long teacherId);

    int deleteAllByTeacherId(Long teacherId);
}
