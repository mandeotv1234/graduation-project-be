package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.infrastructure.persistence.entities.ExamSubmissionEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamSubmissionJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ExamSubmissionRepositoryImpl implements ExamSubmissionRepository {

    private final ExamSubmissionJpaRepository jpaRepository;

    @Override
    public ExamSubmission save(ExamSubmission submission) {
        ExamSubmissionEntity entity = ExamSubmissionEntity.fromModel(submission);
        return jpaRepository.save(entity).toModel();
    }

    @Override
    public Optional<ExamSubmission> findById(Long id) {
        return jpaRepository.findById(id).map(ExamSubmissionEntity::toModel);
    }

    @Override
    public Optional<ExamSubmission> findByExamIdAndQuestionIdAndStudentId(Long examId, Long questionId,
            Long studentId) {
        return jpaRepository.findByExamIdAndQuestionIdAndStudentId(examId, questionId, studentId)
                .map(ExamSubmissionEntity::toModel);
    }

    @Override
    public List<ExamSubmission> findByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber) {
        return jpaRepository.findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber)
                .stream()
                .map(ExamSubmissionEntity::toModel)
                .toList();
    }

    @Override
    public List<ExamSubmission> saveAll(List<ExamSubmission> submissions) {
        List<ExamSubmissionEntity> entities = submissions.stream()
                .map(ExamSubmissionEntity::fromModel)
                .toList();
        return jpaRepository.saveAll(entities).stream()
                .map(ExamSubmissionEntity::toModel)
                .toList();
    }

    @Override
    public List<ExamSubmission> findByExamId(Long examId) {
        return jpaRepository.findByExamId(examId).stream()
                .map(ExamSubmissionEntity::toModel)
                .toList();
    }
}
