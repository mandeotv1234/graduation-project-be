package graduation_project_be.infrastructure.persistence.repositories;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.domain.models.ExamDraft;
import graduation_project_be.infrastructure.persistence.entities.ExamDraftEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamDraftJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ExamDraftRepositoryImpl implements ExamDraftRepository {

    private final ExamDraftJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public ExamDraft save(ExamDraft draft) {
        ExamDraftEntity entity = ExamDraftEntity.fromModel(draft, objectMapper);
        // Upsert: find existing and update id if necessary
        jpaRepository.findByExamIdAndStudentId(draft.getExamId(), draft.getStudentId())
                .ifPresent(existing -> entity.setId(existing.getId()));
        return jpaRepository.save(entity).toModel(objectMapper);
    }

    @Override
    public Optional<ExamDraft> findByExamIdAndStudentId(Long examId, Long studentId) {
        return jpaRepository.findByExamIdAndStudentId(examId, studentId)
                .map(e -> e.toModel(objectMapper));
    }

    @Override
    public void deleteByExamIdAndStudentId(Long examId, Long studentId) {
        jpaRepository.deleteByExamIdAndStudentId(examId, studentId);
    }

    @Override
    public List<ExamDraft> findAll() {
        return jpaRepository.findAll().stream()
                .map(e -> e.toModel(objectMapper))
                .toList();
    }
}
