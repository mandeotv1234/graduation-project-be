package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamTemplateRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
public class HideExamTemplateLineageUsecase {

    private final ExamTemplateRepository examTemplateRepository;
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public void execute(Long sourceExamId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam sourceExam = examRepository.findById(sourceExamId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", sourceExamId));

        if (!currentUserId.equals(sourceExam.getCreatorId())) {
            throw new UnauthorizedException("Only the exam creator can hide this exam template lineage");
        }

        List<ExamTemplate> templates = examTemplateRepository.findBySourceExamIdOrderByVersionDesc(sourceExamId);
        if (templates.isEmpty()) {
            return;
        }

        examTemplateRepository.saveAll(templates.stream()
                .map(template -> ExamTemplate.builder()
                        .id(template.getId())
                        .sourceExamId(template.getSourceExamId())
                        .version(template.getVersion())
                        .sharedBy(template.getSharedBy())
                        .title(template.getTitle())
                        .description(template.getDescription())
                        .specificationSnapshot(template.getSpecificationSnapshot())
                        .createdAt(template.getCreatedAt())
                        .isVisible(false)
                        .build())
                .toList());
    }
}
