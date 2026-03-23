package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.domain.models.User;

import graduation_project_be.infrastructure.persistence.entities.UserEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.PageRequest;

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

    @Override
    public User save(User user) {
        UserEntity entity = UserEntity.fromModel(user);
        return userJpaRepository.save(entity).toModel();
    }

    @Override
    public List<User> saveAll(List<User> users) {
        List<UserEntity> entities = users.stream()
                .map(UserEntity::fromModel)
                .toList();
        return userJpaRepository.saveAll(entities).stream()
                .map(UserEntity::toModel)
                .toList();
    }

    @Override
    public Optional<User> findById(Long id) {
        return userJpaRepository.findById(id)
                .map(UserEntity::toModel);
    }

    @Override
    public List<User> findByIdIn(List<Long> ids, int limit, int offset) {
        int page = offset / limit;
        return userJpaRepository.findByIdIn(ids, PageRequest.of(page, limit))
                .stream()
                .map(UserEntity::toModel)
                .toList();
    }

    @Override
    public List<User> findAllById(List<Long> ids) {
        return userJpaRepository.findAllById(ids).stream()
                .map(UserEntity::toModel)
                .toList();
    }
}
