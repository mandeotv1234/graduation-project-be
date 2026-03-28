package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.CreateExamResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GetTeacherExamSettingsUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public CreateExamResponse execute(Long examId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        if (!hasTeacherAccess(exam, currentUserId)) {
            throw new UnauthorizedException("User does not have access to this exam");
        }

        return CreateExamResponse.fromModel(exam);
    }

    private boolean hasTeacherAccess(Exam exam, Long currentUserId) {
        return currentUserId.equals(exam.getCreatorId())
                || classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
    }
}
