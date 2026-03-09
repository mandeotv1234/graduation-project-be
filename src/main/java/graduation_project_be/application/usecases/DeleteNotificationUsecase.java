package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.TeacherNotificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.DeleteNotificationRequest;
import graduation_project_be.application.usecases.response.DeleteNotificationResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DeleteNotificationUsecase {

    private final TeacherNotificationRepository teacherNotificationRepository;
    private final CurrentUserService currentUserService;

    public DeleteNotificationResponse execute(DeleteNotificationRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();

        if (request.deleteAll()) {
            int count = teacherNotificationRepository.deleteAllByTeacherId(teacherId);
            return DeleteNotificationResponse.ofAll(count);
        }

        boolean deleted = teacherNotificationRepository.deleteByIdAndTeacherId(
                request.notificationId(), teacherId);

        if (!deleted) {
            throw new ResourceNotFoundException("Notification", "id", request.notificationId());
        }

        return DeleteNotificationResponse.ofOne();
    }
}
