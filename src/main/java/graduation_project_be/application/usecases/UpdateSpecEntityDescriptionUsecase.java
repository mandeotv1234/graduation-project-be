package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.SpecEntityRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.UpdateSpecEntityDescriptionRequest;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.SpecEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persist a teacher-edited or AI-generated description onto spec_entity.description.
 * Auth: caller must be class teacher of the exam that references this spec.
 */
@RequiredArgsConstructor
public class UpdateSpecEntityDescriptionUsecase {

    private static final int MAX_DESCRIPTION_LENGTH = 2000;

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final SpecEntityRepository specEntityRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public void execute(UpdateSpecEntityDescriptionRequest request) {
        Long userId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        if (exam.getSpecificationId() == null || !request.specId().equals(exam.getSpecificationId())) {
            throw new BadRequestException("Đặc tả không khớp với đề thi");
        }

        boolean isTeacher = classRepository.existsTeacherAccess(exam.getClassId(), userId);
        if (!isTeacher) {
            throw new UnauthorizedException("You are not a teacher of this exam's class");
        }

        SpecEntity entity = specEntityRepository.findById(request.entityId())
                .orElseThrow(() -> new ResourceNotFoundException("SpecEntity", "id", request.entityId()));

        if (!request.specId().equals(entity.getSpecificationId())) {
            throw new ResourceNotFoundException("SpecEntity", "specificationId", request.specId());
        }

        String description = request.description();
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new BadRequestException("Description exceeds maximum length of " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        specEntityRepository.updateDescription(request.entityId(), description);
    }
}
