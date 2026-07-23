package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.TeacherClassNotificationService;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TeacherClassNotificationServiceImpl implements TeacherClassNotificationService {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void notifyTeacherAdded(Class clazz, User invitedTeacher, User actor) {
        eventPublisher.publishEvent(toEvent(
                TeacherClassMembershipEvent.Action.ADDED, clazz, invitedTeacher, actor));
    }

    @Override
    public void notifyTeacherRemoved(Class clazz, User removedTeacher, User actor) {
        eventPublisher.publishEvent(toEvent(
                TeacherClassMembershipEvent.Action.REMOVED, clazz, removedTeacher, actor));
    }

    private TeacherClassMembershipEvent toEvent(
            TeacherClassMembershipEvent.Action action,
            Class clazz,
            User recipient,
            User actor) {
        return new TeacherClassMembershipEvent(
                action,
                clazz.getId(),
                clazz.getClassCode(),
                clazz.getSemester(),
                recipient.getEmail(),
                recipient.getFullName(),
                actor.getEmail(),
                actor.getFullName());
    }
}
