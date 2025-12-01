package graduation_project_be.user.infrastructure.persistence;

import graduation_project_be.user.application.port.UserRepository;
import graduation_project_be.user.domain.models.User;
import graduation_project_be.user.infrastructure.persistence.UserEntity;
import graduation_project_be.user.infrastructure.persistence.jpa.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpaRepository;

    public Optional<User> findByEmail(String email) {
        Optional<UserEntity> userEntity = userJpaRepository.findByEmail(email);
        return userEntity.map(UserEntity::toModel);
    }
}
