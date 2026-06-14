package graduation_project_be.domain.repositories;

import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.RulePreset;

import java.util.List;
import java.util.Optional;

public interface RulePresetRepository {
    RulePreset save(RulePreset rulePreset);
    Optional<RulePreset> findById(Long id);
    List<RulePreset> findByTeacherIdAndQuestionType(Long teacherId, QuestionType questionType);
    List<RulePreset> findByTeacherIdAndQuestionTypeAndKind(Long teacherId, QuestionType questionType, String kind);
    void deleteById(Long id);
}
