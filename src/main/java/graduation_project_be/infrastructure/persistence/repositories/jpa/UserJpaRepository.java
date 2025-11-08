package graduation_project_be.infrastructure.persistence.repositories.jpa;


import graduation_project_be.infrastructure.persistence.entities.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;


import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);
}
