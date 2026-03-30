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

@RequiredArgsConstructor
public class UpdateExamTemplateVisibilityUsecase {

    private final ExamTemplateRepository examTemplateRepository;
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public void execute(Long templateId, boolean isVisible) {
        Long currentUserId = currentUserService.getCurrentUserId();

        ExamTemplate template = examTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamTemplate", "id", templateId));

        Exam sourceExam = examRepository.findById(template.getSourceExamId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", template.getSourceExamId()));

        if (!currentUserId.equals(sourceExam.getCreatorId())) {
            throw new UnauthorizedException("Only the exam creator can manage template visibility");
        }

        if (Boolean.valueOf(isVisible).equals(template.getIsVisible())) {
            return;
        }

        examTemplateRepository.save(ExamTemplate.builder()
                .id(template.getId())
                .sourceExamId(template.getSourceExamId())
                .version(template.getVersion())
                .sharedBy(template.getSharedBy())
                .title(template.getTitle())
                .description(template.getDescription())
                .specificationSnapshot(template.getSpecificationSnapshot())
                .createdAt(template.getCreatedAt())
                .isVisible(isVisible)
                .build());
    }
}
