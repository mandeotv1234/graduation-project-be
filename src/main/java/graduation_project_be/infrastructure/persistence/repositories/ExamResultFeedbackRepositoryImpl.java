package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamResultFeedbackRepository;
import graduation_project_be.domain.models.ExamResultFeedback;
import graduation_project_be.infrastructure.persistence.entities.ExamResultFeedbackEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamResultFeedbackJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ExamResultFeedbackRepositoryImpl implements ExamResultFeedbackRepository {

    private final ExamResultFeedbackJpaRepository jpaRepository;

    @Override
    public ExamResultFeedback save(ExamResultFeedback feedback) {
        return jpaRepository.save(ExamResultFeedbackEntity.fromModel(feedback)).toModel();
    }

    @Override
    public Optional<ExamResultFeedback> findByExamResultId(Long examResultId) {
        return jpaRepository.findByExamResultId(examResultId)
                .map(ExamResultFeedbackEntity::toModel);
    }
}
