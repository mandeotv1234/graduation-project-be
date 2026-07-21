package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.Feedback;
import graduation_project_be.domain.models.PaginatedResult;

import java.util.Optional;

public interface FeedbackRepository {
    Feedback save(Feedback feedback);
    boolean existsByExamIdAndStudentId(Long examId, Long studentId);
    Optional<Feedback> findById(Long id);
    PaginatedResult<Feedback> findAll(int page, int size);
}
