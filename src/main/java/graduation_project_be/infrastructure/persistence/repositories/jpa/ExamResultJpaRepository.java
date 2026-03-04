package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamResultJpaRepository extends JpaRepository<ExamResultEntity, Long> {
}
