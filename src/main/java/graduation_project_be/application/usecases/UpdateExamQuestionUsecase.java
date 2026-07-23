package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.TestCaseRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.UpdateExamQuestionRequest;
import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.TestCase;
import graduation_project_be.infrastructure.services.ExpectedValueDeriver;
import graduation_project_be.infrastructure.services.RubricToTestCaseTransformer;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import graduation_project_be.application.usecases.support.ExamSettingsValidator;

@RequiredArgsConstructor
public class UpdateExamQuestionUsecase {

    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final RubricToTestCaseTransformer rubricTransformer;
    private final ExpectedValueDeriver expectedValueDeriver;
    private final TestCaseRepository testCaseRepository;

    @Transactional
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

        ExamSettingsValidator.validateQuestionPoints(request.points());
        ExamSettingsValidator.validateQuestionMetadata(
                request.difficultyLevel(), request.orderIndex());
        List<BigDecimal> totalPoints = examQuestionRepository.findByExamId(request.examId()).stream()
                .filter(item -> !item.getId().equals(question.getId()))
                .map(ExamQuestion::getPoints).toList();
        totalPoints = new java.util.ArrayList<>(totalPoints);
        totalPoints.add(request.points());
        ExamSettingsValidator.validateTotalPoints(totalPoints);

        question.setContent(request.content());
        question.setCorrectQuery(request.correctQuery());
        question.setVerifyScript(request.verifyScript());
        question.setDifficultyLevel(request.difficultyLevel());
        question.setPoints(request.points());
        question.setOrderIndex(request.orderIndex());
        QuestionType questionType = QuestionType.valueOf(request.questionType().toUpperCase());
        question.setQuestionType(questionType);
        question.setGradingRubric(request.gradingRubric());

        ExamQuestion updated = examQuestionRepository.save(question);
        if (isRoutineOrTriggerType(questionType)
                && request.gradingRubric() != null
                && !request.gradingRubric().isBlank()) {
            syncRubricTestCases(updated, exam);
        }
        return ExamQuestionResponse.fromModel(updated);
    }

    private boolean isRoutineOrTriggerType(QuestionType type) {
        return type == QuestionType.STORED_PROCEDURE
                || type == QuestionType.FUNCTION
                || type == QuestionType.TRIGGER;
    }

    private void syncRubricTestCases(ExamQuestion question, Exam exam) {
        List<TestCase> testCases = rubricTransformer.parse(
                question.getId(), question.getQuestionType().name(), question.getGradingRubric());
        if (testCases.isEmpty()) {
            throw new BadRequestException(String.format(
                    "Q%d (%s): rubric không có test_cases hợp lệ. Vui lòng kiểm tra lại đề.",
                    question.getOrderIndex(), question.getQuestionType()));
        }

        ExamSpecification specification = exam.getSpecificationId() != null
                ? examSpecificationRepository.findById(exam.getSpecificationId()).orElse(null)
                : null;
        String ddlScript = specification != null ? specification.getDdlScript() : null;

        ExpectedValueDeriver.DerivationResult result;
        try {
            result = expectedValueDeriver.derive(
                    question.getId(), ddlScript, question.getCorrectQuery(), testCases);
        } catch (ExpectedValueDeriver.DerivationException e) {
            throw new BadRequestException(String.format(
                    "Q%d (%s): %s",
                    question.getOrderIndex(), question.getQuestionType(), e.getMessage()));
        }

        if (!result.isFullySuccessful()) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Q%d (%s): có test case không derive được expected:\n",
                    question.getOrderIndex(), question.getQuestionType()));
            for (Map.Entry<Integer, String> entry : result.testCaseErrors().entrySet()) {
                sb.append("  - TC").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("Hãy điều chỉnh setup_script / invocation_query trong rubric và thử lại.");
            throw new BadRequestException(sb.toString());
        }

        testCaseRepository.deleteByQuestionId(question.getId());
        rubricTransformer.persist(question.getId(), testCases);
    }
}
