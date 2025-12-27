package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.Exam;

import java.util.Optional;

public interface ExamRepository {
    Optional<Exam> findByIdAndIsPublished(Long id, Boolean isPublished);
}
