package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.SpecEntityRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.usecases.response.GenerateSpecEntityDescriptionResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.SpecEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generate an AI description for a spec entity (no persist).
 * Auth: caller must be class teacher of the exam that uses this spec.
 */
@Slf4j
@RequiredArgsConstructor
public class GenerateSpecEntityDescriptionUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final SpecEntityRepository specEntityRepository;
    private final CurrentUserService currentUserService;
    private final AIService aiService;

    public GenerateSpecEntityDescriptionResponse execute(Long specId, Long entityId, Long examId) {
        Long userId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        if (exam.getSpecificationId() == null || !specId.equals(exam.getSpecificationId())) {
            throw new BadRequestException("Đặc tả không khớp với đề thi");
        }

        boolean isTeacher = classRepository.existsTeacherAccess(exam.getClassId(), userId);
        if (!isTeacher) {
            throw new UnauthorizedException("Bạn không phải giảng viên của lớp đề thi này");
        }

        SpecEntity entity = specEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("SpecEntity", "id", entityId));

        if (!specId.equals(entity.getSpecificationId())) {
            throw new ResourceNotFoundException("SpecEntity", "specificationId", specId);
        }

        String description = aiService.generateEntityDescription(
                entity.getEntityName(),
                entity.getDisplayName(),
                entity.getAttributes(),
                null);

        return new GenerateSpecEntityDescriptionResponse(entityId, entity.getEntityName(), description);
    }
}
