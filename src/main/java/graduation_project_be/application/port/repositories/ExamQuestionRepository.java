package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamQuestion;
import java.util.List;
import java.util.Optional;

public interface ExamQuestionRepository {
    ExamQuestion save(ExamQuestion examQuestion);

    List<ExamQuestion> saveAll(List<ExamQuestion> examQuestions);

    List<ExamQuestion> findByExamId(Long examId);

    Optional<ExamQuestion> findById(Long id);

    void deleteById(Long id);
}
