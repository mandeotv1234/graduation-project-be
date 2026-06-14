package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.infrastructure.persistence.entities.RulePresetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JpaRulePresetRepository extends JpaRepository<RulePresetEntity, Long> {
    List<RulePresetEntity> findByTeacherIdAndQuestionTypeOrderByCreatedAtDesc(Long teacherId, QuestionType questionType);
    List<RulePresetEntity> findByTeacherIdAndQuestionTypeAndKindOrderByCreatedAtDesc(Long teacherId, QuestionType questionType, String kind);
}
