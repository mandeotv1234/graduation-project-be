package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.ExamQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamQuestionJpaRepository extends JpaRepository<ExamQuestionEntity, Long> {
    List<ExamQuestionEntity> findByExamIdOrderByOrderIndexAsc(Long examId);
}
