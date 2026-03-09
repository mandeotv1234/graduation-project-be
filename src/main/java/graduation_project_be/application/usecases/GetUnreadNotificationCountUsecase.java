package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.TeacherNotificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.UnreadCountResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GetUnreadNotificationCountUsecase {

    private final TeacherNotificationRepository teacherNotificationRepository;
    private final CurrentUserService currentUserService;

    public UnreadCountResponse execute() {
        Long teacherId = currentUserService.getCurrentUserId();
        long count = teacherNotificationRepository.countUnreadByTeacherId(teacherId);
        return new UnreadCountResponse(count);
    }
}
