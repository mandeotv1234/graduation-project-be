package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamTemplate;

import java.util.List;
import java.util.Optional;

public interface ExamTemplateRepository {
    ExamTemplate save(ExamTemplate template);
    List<ExamTemplate> saveAll(List<ExamTemplate> templates);
    Optional<ExamTemplate> findById(Long id);
    Optional<ExamTemplate> findVisibleById(Long id);
    List<ExamTemplate> findVisibleOrderBySourceExamIdAscVersionDesc();
    List<ExamTemplate> findVisibleBySourceExamIdOrderByVersionDesc(Long sourceExamId);
    List<ExamTemplate> findBySourceExamIdOrderByVersionDesc(Long sourceExamId);
    int findNextVersion(Long sourceExamId);
}
