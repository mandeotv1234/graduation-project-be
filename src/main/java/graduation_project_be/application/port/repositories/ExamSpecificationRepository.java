package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamSpecification;

import java.util.Optional;

public interface ExamSpecificationRepository {
    ExamSpecification save(ExamSpecification specification);
    Optional<ExamSpecification> findByTemplateId(Long templateId);
    boolean existsByTemplateId(Long templateId);
    void deleteByTemplateId(Long templateId);
}
