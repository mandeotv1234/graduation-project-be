package graduation_project_be.application.port.repositories;


import graduation_project_be.domain.models.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    Optional<User> findByEmail(String email);
    User save(User user);
    List<User> saveAll(List<User> users);
    Optional<User> findById(Long id);
    List<User> findByIdIn(List<Long> ids, int limit, int offset);
}
