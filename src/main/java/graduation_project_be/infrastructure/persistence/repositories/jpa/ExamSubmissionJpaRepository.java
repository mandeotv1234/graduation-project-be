package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamSubmissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamSubmissionJpaRepository extends JpaRepository<ExamSubmissionEntity, Long> {
    Optional<ExamSubmissionEntity> findByExamIdAndQuestionIdAndStudentId(Long examId, Long questionId, Long studentId);

    List<ExamSubmissionEntity> findByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber);
}
