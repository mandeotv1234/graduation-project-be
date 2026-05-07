package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.FeedbackRepository;
import graduation_project_be.domain.models.Feedback;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.infrastructure.persistence.entities.FeedbackEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.FeedbackJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class FeedbackRepositoryImpl implements FeedbackRepository {
    private final FeedbackJpaRepository feedbackJpaRepository;

    @Override
    public Feedback save(Feedback feedback) {
        FeedbackEntity entity = FeedbackEntity.fromModel(feedback);
        return feedbackJpaRepository.save(entity).toModel();
    }

    @Override
    public boolean existsByExamIdAndStudentId(Long examId, Long studentId) {
        return feedbackJpaRepository.existsByExamIdAndStudentId(examId, studentId);
    }

    @Override
    public PaginatedResult<Feedback> findAll(int page, int size) {
        Page<FeedbackEntity> entityPage = feedbackJpaRepository.findAll(
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
        List<Feedback> feedbacks = entityPage.getContent().stream()
                .map(FeedbackEntity::toModel)
                .toList();
        return PaginatedResult.of(feedbacks, page, size, entityPage.getTotalElements());
    }
}
