package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamDraft;

import java.util.Optional;
import java.util.List;

public interface ExamDraftRepository {
    ExamDraft save(ExamDraft draft);
    Optional<ExamDraft> findByExamIdAndStudentId(Long examId, Long studentId);
    void deleteByExamIdAndStudentId(Long examId, Long studentId);
    List<ExamDraft> findAll();
}
