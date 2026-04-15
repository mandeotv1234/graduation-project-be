package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamResult;

import java.util.List;
import java.util.Optional;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;

public interface ExamResultRepository {
    ExamResult save(ExamResult examResult);
    Optional<ExamResult> findById(Long id);

    Optional<ExamResult> findByExamIdAndStudentId(Long examId, Long studentId);

    Optional<ExamResult> findByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber);

    Long countByExamIdAndStudentId(Long examId, Long studentId);
    List<ExamResult> findByExamId(Long examId);
    
    PaginatedResult<ExamResult> findPaginatedByStudentId(Long studentId, PaginationParams params);
}
