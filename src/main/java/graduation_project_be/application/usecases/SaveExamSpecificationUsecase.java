package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.SaveExamSpecificationRequest;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
public class SaveExamSpecificationUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public ExamSpecificationResponse execute(SaveExamSpecificationRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        if (!exam.getCreatorId().equals(currentUserId)) {
            throw new UnauthorizedException("You are not the creator of this exam");
        }

        List<SpecEntity> entities = request.entities() == null ? List.of()
                : request.entities().stream().map(e -> {
                    List<SpecAttribute> attributes = e.attributes() == null ? List.of()
                            : e.attributes().stream().map(a -> SpecAttribute.builder()
                                    .attributeName(a.attributeName())
                                    .dataType(a.dataType())
                                    .description(a.description())
                                    .isPrimaryKey(a.isPrimaryKey())
                                    .isNullable(a.isNullable())
                                    .orderIndex(a.orderIndex())
                                    .build()).toList();
                    return SpecEntity.builder()
                            .entityName(e.entityName())
                            .displayName(e.displayName())
                            .description(e.description())
                            .orderIndex(e.orderIndex())
                            .attributes(attributes)
                            .build();
                }).toList();

        LocalDateTime now = LocalDateTime.now();
        ExamSpecification specification = ExamSpecification.builder()
                .templateId(exam.getTemplateId())
                .title(request.title())
                .description(request.description())
                .entities(entities)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // Upsert: xoá cũ nếu đã tồn tại
        if (examSpecificationRepository.existsByTemplateId(exam.getTemplateId())) {
            examSpecificationRepository.deleteByTemplateId(exam.getTemplateId());
        }

        ExamSpecification saved = examSpecificationRepository.save(specification);
        return ExamSpecificationResponse.fromModel(saved);
    }
}
