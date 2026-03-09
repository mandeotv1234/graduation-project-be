package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamViolation;

import java.util.List;

public interface ExamViolationRepository {
    ExamViolation save(ExamViolation violation);
    List<ExamViolation> findByExamId(Long examId);
    List<ExamViolation> findByExamIdAndStudentId(Long examId, Long studentId);
    long countByExamIdAndStudentId(Long examId, Long studentId);
}
