package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamTemplateRepository;
import graduation_project_be.domain.models.ExamTemplate;
import graduation_project_be.infrastructure.persistence.entities.ExamTemplateEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamTemplateJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ExamTemplateRepositoryImpl implements ExamTemplateRepository {

    private final ExamTemplateJpaRepository examTemplateJpaRepository;

    @Override
    public ExamTemplate save(ExamTemplate template) {
        return examTemplateJpaRepository.save(ExamTemplateEntity.fromModel(template)).toModel();
    }

    @Override
    public List<ExamTemplate> saveAll(List<ExamTemplate> templates) {
        return examTemplateJpaRepository.saveAll(
                        templates.stream().map(ExamTemplateEntity::fromModel).toList())
                .stream()
                .map(ExamTemplateEntity::toModel)
                .toList();
    }

    @Override
    public Optional<ExamTemplate> findById(Long id) {
        return examTemplateJpaRepository.findById(id)
                .map(ExamTemplateEntity::toModel);
    }

    @Override
    public Optional<ExamTemplate> findVisibleById(Long id) {
        return examTemplateJpaRepository.findByIdAndIsVisibleTrue(id)
                .map(ExamTemplateEntity::toModel);
    }

    @Override
    public List<ExamTemplate> findVisibleOrderBySourceExamIdAscVersionDesc() {
        return examTemplateJpaRepository.findByIsVisibleTrueOrderBySourceExamIdAscVersionDesc().stream()
                .map(ExamTemplateEntity::toModel)
                .toList();
    }

    @Override
    public List<ExamTemplate> findVisibleBySourceExamIdOrderByVersionDesc(Long sourceExamId) {
        return examTemplateJpaRepository.findBySourceExamIdAndIsVisibleTrueOrderByVersionDesc(sourceExamId).stream()
                .map(ExamTemplateEntity::toModel)
                .toList();
    }

    @Override
    public List<ExamTemplate> findBySourceExamIdOrderByVersionDesc(Long sourceExamId) {
        return examTemplateJpaRepository.findBySourceExamIdOrderByVersionDesc(sourceExamId).stream()
                .map(ExamTemplateEntity::toModel)
                .toList();
    }

    @Override
    public int findNextVersion(Long sourceExamId) {
        return examTemplateJpaRepository.findMaxVersionBySourceExamId(sourceExamId) + 1;
    }
}
