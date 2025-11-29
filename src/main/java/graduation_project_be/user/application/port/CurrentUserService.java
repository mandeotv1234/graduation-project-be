package graduation_project_be.user.application.port;

import graduation_project_be.user.domain.User;

public interface CurrentUserService {
    User getCurrentUser();
    Long getCurrentUserId();
}
