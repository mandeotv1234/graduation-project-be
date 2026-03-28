package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamTemplateQuestion;

import java.util.List;
import java.util.Map;

public interface ExamTemplateQuestionRepository {
    List<ExamTemplateQuestion> saveAll(List<ExamTemplateQuestion> questions);
    List<ExamTemplateQuestion> findByTemplateId(Long templateId);
    long countByTemplateId(Long templateId);
    Map<Long, Long> countByTemplateIds(List<Long> templateIds);
}
