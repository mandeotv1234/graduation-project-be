package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamResultFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExamResultFeedbackJpaRepository extends JpaRepository<ExamResultFeedbackEntity, Long> {
    Optional<ExamResultFeedbackEntity> findByExamResultId(Long examResultId);
}
