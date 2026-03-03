package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.CreateExamQuestionRequest;
import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.QuestionType;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CreateExamQuestionUsecase {

    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;

    public ExamQuestionResponse execute(CreateExamQuestionRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new IllegalArgumentException("Exam not found"));

        if (!exam.getCreatorId().equals(currentUserId)) {
            throw new UnauthorizedException("Only the exam creator can add questions");
        }

        // Validate & parse question type
        QuestionType questionType;
        try {
            questionType = request.questionType() != null
                    ? QuestionType.valueOf(request.questionType())
                    : QuestionType.SELECT_QUERY;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid question type. Must be one of: CREATE_TABLE, INSERT_DATA, SELECT_QUERY, TRIGGER, FUNCTION, STORED_PROCEDURE");
        }

        ExamQuestion question = ExamQuestion.builder()
                .examId(request.examId())
                .content(request.content())
                .correctQuery(request.correctQuery())
                .difficultyLevel(request.difficultyLevel() != null ? request.difficultyLevel() : 1)
                .points(request.points())
                .orderIndex(request.orderIndex())
                .questionType(questionType)
                .verifyScript(request.verifyScript())
                .build();

        ExamQuestion saved = examQuestionRepository.save(question);
        return ExamQuestionResponse.fromModel(saved);
    }
}
