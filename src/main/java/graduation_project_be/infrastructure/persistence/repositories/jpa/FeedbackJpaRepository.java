package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.FeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FeedbackJpaRepository extends JpaRepository<FeedbackEntity, Long> {
    boolean existsByExamIdAndStudentId(Long examId, Long studentId);
}
