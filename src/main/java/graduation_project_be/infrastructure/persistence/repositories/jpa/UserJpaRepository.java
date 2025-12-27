package graduation_project_be.infrastructure.persistence.repositories.jpa;


import graduation_project_be.infrastructure.persistence.entities.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);
    
    @Query("SELECT u FROM UserEntity u WHERE u.id IN :ids")
    Page<UserEntity> findByIdIn(@Param("ids") List<Long> ids, Pageable pageable);
}
