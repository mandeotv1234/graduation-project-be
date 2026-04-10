package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.Exam;

import java.util.List;
import java.util.Optional;

public interface ExamRepository {
    Optional<Exam> findByIdAndIsPublished(Long id, Boolean isPublished);

    Optional<Exam> findById(Long id);

    Exam save(Exam exam);

    List<Exam> findByClassId(Long classId);

    List<Exam> findPublishedExamsByStudentId(Long studentId);

    boolean existsBySpecificationId(Long specificationId);
}
