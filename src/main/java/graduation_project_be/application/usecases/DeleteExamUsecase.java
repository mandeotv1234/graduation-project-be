package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.application.port.services.CurrentUserService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DeleteExamUsecase {
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;

    public void execute(Long examId) {
        Long teacherId = currentUserService.getCurrentUserId();
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        if (!exam.getCreatorId().equals(teacherId)) {
            throw new UnauthorizedException("You do not have permission to delete this exam");
        }

        examRepository.deleteById(examId);
    }
}
