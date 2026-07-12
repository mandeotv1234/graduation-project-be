package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.UpdateTeacherExamSettingsRequest;
import graduation_project_be.application.usecases.response.CreateExamResponse;
import graduation_project_be.application.usecases.support.ExamSettingsValidator;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class UpdateTeacherExamSettingsUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final ExamSpecificationRepository examSpecificationRepository;

    @Transactional
    public CreateExamResponse execute(Long examId, UpdateTeacherExamSettingsRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        if (!hasTeacherAccess(exam, currentUserId)) {
            throw new UnauthorizedException("User does not have access to this exam");
        }

        Exam updatedExam = Exam.builder()
                .id(exam.getId())
                .specificationId(exam.getSpecificationId())
                .classId(exam.getClassId())
                .creatorId(exam.getCreatorId())
                .title(request.title())
                .durationMinutes(request.durationMinutes())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .isPublished(request.isPublished() != null ? request.isPublished() : exam.getIsPublished())
                .createdAt(exam.getCreatedAt())
                .description(request.description())
                .maxAttempts(request.maxAttempts())
                .lateThreshold(request.lateThreshold())
                .settings(request.settings())
                .build();

        ExamSettingsValidator.validateDatabaseInitialization(
                examSpecificationRepository,
                updatedExam.getSpecificationId(),
                updatedExam.getSettings());
        ExamSettingsValidator.validateExamTimeWindow(updatedExam.getStartTime(), updatedExam.getEndTime());

        return CreateExamResponse.fromModel(examRepository.save(updatedExam));
    }

    private boolean hasTeacherAccess(Exam exam, Long currentUserId) {
        return currentUserId.equals(exam.getCreatorId())
                || classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
    }
}
