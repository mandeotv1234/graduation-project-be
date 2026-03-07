package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.infrastructure.persistence.entities.ExamEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ExamRepositoryImpl implements ExamRepository {

    private final ExamJpaRepository examJpaRepository;

    @Override
    public Optional<Exam> findByIdAndIsPublished(Long id, Boolean isPublished) {
        return examJpaRepository.findByIdAndIsPublished(id, isPublished)
                .map(ExamEntity::toModel);
    }

    @Override
    public Optional<Exam> findById(Long id) {
        return examJpaRepository.findById(id)
                .map(ExamEntity::toModel);
    }

    @Override
    public Exam save(Exam exam) {
        ExamEntity entity = ExamEntity.fromModel(exam);
        return examJpaRepository.save(entity).toModel();
    }

    @Override
    public List<Exam> findByClassId(Long classId) {
        return examJpaRepository.findByClassId(classId).stream()
                .map(ExamEntity::toModel)
                .toList();
    }

    @Override
    public List<Exam> findPublishedExamsByStudentId(Long studentId) {
        return examJpaRepository.findPublishedExamsByStudentId(studentId).stream()
                .map(ExamEntity::toModel)
                .toList();
    }
}
