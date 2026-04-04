package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.UpdateExamQuestionRequest;
import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpdateExamQuestionUsecase {

    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public ExamQuestionResponse execute(UpdateExamQuestionRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new RuntimeException("Exam not found"));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        if (!hasAccess) {
            throw new UnauthorizedException("You are not authorized to update details of this exam");
        }

        ExamQuestion question = examQuestionRepository.findById(request.questionId())
                .orElseThrow(() -> new RuntimeException("Question not found"));

        if (!question.getExamId().equals(request.examId())) {
            throw new RuntimeException("Question does not belong to this exam");
        }

        question.setContent(request.content());
        question.setCorrectQuery(request.correctQuery());
        question.setVerifyScript(request.verifyScript());
        question.setDifficultyLevel(request.difficultyLevel());
        question.setPoints(request.points());
        question.setOrderIndex(request.orderIndex());
        question.setQuestionType(graduation_project_be.domain.models.QuestionType.valueOf(request.questionType().toUpperCase()));
        question.setGradingRubric(request.gradingRubric());

        ExamQuestion updated = examQuestionRepository.save(question);
        return ExamQuestionResponse.fromModel(updated);
    }
}
