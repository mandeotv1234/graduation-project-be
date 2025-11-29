package graduation_project_be.user.application.port;


import graduation_project_be.user.domain.User;

import java.util.Optional;

public interface UserRepository {

    Optional<User> findByEmail(String email);
}
