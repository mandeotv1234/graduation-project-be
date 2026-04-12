package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.RulePreset;
import graduation_project_be.domain.repositories.RulePresetRepository;
import graduation_project_be.infrastructure.persistence.entities.RulePresetEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class RulePresetRepositoryImpl implements RulePresetRepository {

    private final JpaRulePresetRepository jpaRulePresetRepository;

    @Override
    @Transactional
    public RulePreset save(RulePreset rulePreset) {
        RulePresetEntity entity = RulePresetEntity.fromModel(rulePreset);
        RulePresetEntity saved = jpaRulePresetRepository.save(entity);
        return saved.toModel();
    }

    @Override
    public Optional<RulePreset> findById(Long id) {
        return jpaRulePresetRepository.findById(id)
                .map(RulePresetEntity::toModel);
    }

    @Override
    public List<RulePreset> findByTeacherIdAndQuestionType(Long teacherId, QuestionType questionType) {
        return jpaRulePresetRepository.findByTeacherIdAndQuestionTypeOrderByCreatedAtDesc(teacherId, questionType)
                .stream()
                .map(RulePresetEntity::toModel)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        jpaRulePresetRepository.deleteById(id);
    }
}
