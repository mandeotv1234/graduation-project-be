package graduation_project_be.application.port.services;

import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.User;

public interface TeacherClassNotificationService {

    void notifyTeacherAdded(Class clazz, User invitedTeacher, User actor);

    void notifyTeacherRemoved(Class clazz, User removedTeacher, User actor);
}
