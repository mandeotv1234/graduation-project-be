package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamResultFeedback;

import java.util.Optional;

public interface ExamResultFeedbackRepository {
    ExamResultFeedback save(ExamResultFeedback feedback);

    Optional<ExamResultFeedback> findByExamResultId(Long examResultId);
}
