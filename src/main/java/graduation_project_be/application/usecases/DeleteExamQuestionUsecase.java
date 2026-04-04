package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DeleteExamQuestionUsecase {

    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public void execute(Long examId, Long questionId) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new RuntimeException("Exam not found"));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        if (!hasAccess) {
            throw new UnauthorizedException("You are not authorized to update details of this exam");
        }

        ExamQuestion question = examQuestionRepository.findById(questionId)
                .orElseThrow(() -> new RuntimeException("Question not found"));

        if (!question.getExamId().equals(examId)) {
            throw new RuntimeException("Question does not belong to this exam");
        }

        examQuestionRepository.deleteById(questionId);
    }
}
