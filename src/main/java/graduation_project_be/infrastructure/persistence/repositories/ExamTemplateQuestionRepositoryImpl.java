package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamTemplateQuestionRepository;
import graduation_project_be.domain.models.ExamTemplateQuestion;
import graduation_project_be.infrastructure.persistence.entities.ExamTemplateQuestionEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamTemplateQuestionJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ExamTemplateQuestionRepositoryImpl implements ExamTemplateQuestionRepository {

    private final ExamTemplateQuestionJpaRepository examTemplateQuestionJpaRepository;

    @Override
    public List<ExamTemplateQuestion> saveAll(List<ExamTemplateQuestion> questions) {
        return examTemplateQuestionJpaRepository.saveAll(
                        questions.stream().map(ExamTemplateQuestionEntity::fromModel).toList())
                .stream()
                .map(ExamTemplateQuestionEntity::toModel)
                .toList();
    }

    @Override
    public List<ExamTemplateQuestion> findByTemplateId(Long templateId) {
        return examTemplateQuestionJpaRepository.findByTemplateIdOrderByOrderIndexAsc(templateId).stream()
                .map(ExamTemplateQuestionEntity::toModel)
                .toList();
    }

    @Override
    public long countByTemplateId(Long templateId) {
        return examTemplateQuestionJpaRepository.countByTemplateId(templateId);
    }

    @Override
    public Map<Long, Long> countByTemplateIds(List<Long> templateIds) {
        if (templateIds.isEmpty()) {
            return Map.of();
        }

        return examTemplateQuestionJpaRepository.countByTemplateIds(templateIds).stream()
                .collect(Collectors.toMap(
                        ExamTemplateQuestionJpaRepository.TemplateQuestionCountProjection::getTemplateId,
                        ExamTemplateQuestionJpaRepository.TemplateQuestionCountProjection::getQuestionCount
                ));
    }
}
