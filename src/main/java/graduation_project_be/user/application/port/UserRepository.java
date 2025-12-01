package graduation_project_be.user.application.port;


import java.util.Optional;

import graduation_project_be.user.domain.models.User;

public interface UserRepository {

    Optional<User> findByEmail(String email);
}
