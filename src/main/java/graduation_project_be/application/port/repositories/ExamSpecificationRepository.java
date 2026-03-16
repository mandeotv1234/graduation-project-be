package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamSpecification;

import java.util.List;
import java.util.Optional;

public interface ExamSpecificationRepository {
    ExamSpecification save(ExamSpecification specification);
    List<ExamSpecification> findAll();
    Optional<ExamSpecification> findById(Long id);
    boolean existsById(Long id);
    void deleteById(Long id);
}
