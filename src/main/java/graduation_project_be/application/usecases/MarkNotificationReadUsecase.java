package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.TeacherNotificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MarkNotificationReadUsecase {

    private final TeacherNotificationRepository teacherNotificationRepository;
    private final CurrentUserService currentUserService;

    public boolean execute(Long notificationId) {
        Long teacherId = currentUserService.getCurrentUserId();
        return teacherNotificationRepository.markAsRead(notificationId, teacherId);
    }

    public int markAllAsRead() {
        Long teacherId = currentUserService.getCurrentUserId();
        return teacherNotificationRepository.markAllAsRead(teacherId);
    }
}
