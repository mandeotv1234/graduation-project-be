package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.infrastructure.persistence.entities.ExamQuestionEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamQuestionJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ExamQuestionRepositoryImpl implements ExamQuestionRepository {

    private final ExamQuestionJpaRepository jpaRepository;

    @Override
    public ExamQuestion save(ExamQuestion examQuestion) {
        ExamQuestionEntity entity = ExamQuestionEntity.fromModel(examQuestion);
        return jpaRepository.save(entity).toModel();
    }

    @Override
    public List<ExamQuestion> saveAll(List<ExamQuestion> examQuestions) {
        List<ExamQuestionEntity> entities = examQuestions.stream()
                .map(ExamQuestionEntity::fromModel)
                .toList();
        return jpaRepository.saveAll(entities).stream()
                .map(ExamQuestionEntity::toModel)
                .toList();
    }

    @Override
    public List<ExamQuestion> findByExamId(Long examId) {
        return jpaRepository.findByExamIdOrderByOrderIndexAsc(examId).stream()
                .map(ExamQuestionEntity::toModel)
                .toList();
    }

    @Override
    public Optional<ExamQuestion> findById(Long id) {
        return jpaRepository.findById(id).map(ExamQuestionEntity::toModel);
    }
}
