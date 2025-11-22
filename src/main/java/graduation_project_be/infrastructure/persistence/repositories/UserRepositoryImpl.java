package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.domain.models.User;

import graduation_project_be.infrastructure.persistence.entities.UserEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.UserJpaRepository;
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
