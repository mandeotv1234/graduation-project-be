package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.infrastructure.persistence.entities.ExamResultEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.ExamResultJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Repository
@RequiredArgsConstructor
public class ExamResultRepositoryImpl implements ExamResultRepository {

    private final ExamResultJpaRepository jpaRepository;

    @Override
    public ExamResult save(ExamResult examResult) {
        ExamResultEntity entity = ExamResultEntity.fromDomain(examResult);
        ExamResultEntity saved = jpaRepository.save(entity);
        return saved.toModel();
    }

    @Override
    public Optional<ExamResult> findById(Long id) {
        return jpaRepository.findById(id).map(ExamResultEntity::toModel);
    }

    @Override
    public Optional<ExamResult> findByExamIdAndStudentId(Long examId, Long studentId) {
        return jpaRepository.findFirstByExamIdAndStudentIdOrderByAttemptNumberDesc(examId, studentId)
                .map(ExamResultEntity::toModel);
    }

    @Override
    public Optional<ExamResult> findByExamIdAndStudentIdAndAttemptNumber(Long examId, Long studentId, int attemptNumber) {
        return jpaRepository.findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber)
                .map(ExamResultEntity::toModel);
    }

    @Override
    public Long countByExamIdAndStudentId(Long examId, Long studentId) {
        return jpaRepository.countByExamIdAndStudentId(examId, studentId);
    }

    @Override
    public List<ExamResult> findByExamId(Long examId) {
        return jpaRepository.findByExamId(examId).stream()
                .map(ExamResultEntity::toModel)
                .toList();
    }

    @Override
    public List<ExamResult> findRecoverableGradingResults(
            List<GradingStatus> inFlightStatuses,
            List<GradingStatus> failedStatuses,
            LocalDateTime inFlightSubmittedBefore,
            LocalDateTime failedLastAttemptBefore,
            int limit) {
        Pageable pageable = PageRequest.of(0, Math.max(1, limit));
        return jpaRepository.findRecoverableGradingResults(
                        inFlightStatuses,
                        failedStatuses,
                        inFlightSubmittedBefore,
                        failedLastAttemptBefore,
                        pageable)
                .stream()
                .map(ExamResultEntity::toModel)
                .toList();
    }

    @Override
    public List<ExamResult> findByStudentIdAndExamIdIn(Long studentId, List<Long> examIds) {
        return jpaRepository.findByStudentIdAndExamIdIn(studentId, examIds).stream()
                .map(ExamResultEntity::toModel)
                .toList();
    }

    @Override
    public PaginatedResult<ExamResult> findPaginatedByStudentId(Long studentId, PaginationParams params) {
        Sort sort = params.getSortOrder() == graduation_project_be.domain.models.enums.SortDirection.DESC
                ? Sort.by(params.getSortBy().getFieldName()).descending()
                : Sort.by(params.getSortBy().getFieldName()).ascending();

        Pageable pageable = PageRequest.of(params.getPage(), params.getSize(), sort);

        Page<ExamResultEntity> page = jpaRepository.findByStudentId(studentId, pageable);

        return PaginatedResult.of(
                page.getContent().stream().map(ExamResultEntity::toModel).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()
        );
    }
}
