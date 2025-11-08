package graduation_project_be.application.port.services;

import graduation_project_be.domain.models.User;

public interface CurrentUserService {
    User getCurrentUser();
    Long getCurrentUserId();
}
