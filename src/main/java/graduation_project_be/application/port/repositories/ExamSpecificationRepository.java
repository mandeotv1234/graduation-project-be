package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamSpecification;

import java.util.Optional;

public interface ExamSpecificationRepository {
    ExamSpecification save(ExamSpecification specification);
    Optional<ExamSpecification> findByExamId(Long examId);
    boolean existsByExamId(Long examId);
    void deleteByExamId(Long examId);
}
