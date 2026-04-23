package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamSubmission;
import java.util.List;
import java.util.Optional;

public interface ExamSubmissionRepository {
    ExamSubmission save(ExamSubmission submission);

    Optional<ExamSubmission> findById(Long id);

    Optional<ExamSubmission> findByExamIdAndQuestionIdAndStudentId(Long examId, Long questionId, Long studentId);

    List<ExamSubmission> findByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber);

    List<ExamSubmission> saveAll(List<ExamSubmission> submissions);

    List<ExamSubmission> findByExamId(Long examId);
}
