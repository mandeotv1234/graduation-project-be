package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ExamJpaRepository extends JpaRepository<ExamEntity, Long> {
    Optional<ExamEntity> findByIdAndIsPublished(Long id, Boolean isPublished);
}
