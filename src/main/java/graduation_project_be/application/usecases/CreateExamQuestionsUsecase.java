package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.GeminiService;
import graduation_project_be.application.usecases.request.CreateExamQuestionsRequest;
import graduation_project_be.application.usecases.response.CreateExamQuestionsResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.QuestionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class CreateExamQuestionsUsecase {

    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final CurrentUserService currentUserService;
    private final GeminiService geminiService;

    @Transactional
    public CreateExamQuestionsResponse execute(CreateExamQuestionsRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id: " + request.examId()));

        if (!exam.getCreatorId().equals(currentUserId)) {
            throw new UnauthorizedException("Only the exam creator can add questions");
        }

        // Build schema context from specification (if exists) for better AI prompts
        String schemaContext = buildSchemaContext(request.examId());

        List<ExamQuestion> questionsToSave = new ArrayList<>();

        for (CreateExamQuestionsRequest.QuestionItem item : request.questions()) {
            QuestionType questionType;
            try {
                questionType = item.questionType() != null
                        ? QuestionType.valueOf(item.questionType())
                        : QuestionType.SELECT_QUERY;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid question type: " + item.questionType()
                        + ". Must be one of: CREATE_TABLE, INSERT_DATA, SELECT_QUERY, TRIGGER, FUNCTION, STORED_PROCEDURE");
            }

            // Call Gemini to generate correctQuery + verifyScript
            log.info("Calling Gemini for question: {}", item.content());
            GeminiService.GeneratedQuestion generated = geminiService.generateSqlAnswer(
                    item.content(),
                    questionType.name(),
                    schemaContext);

            ExamQuestion question = ExamQuestion.builder()
                    .examId(request.examId())
                    .content(item.content())
                    .correctQuery(generated.correctQuery())
                    .difficultyLevel(item.difficultyLevel() != null ? item.difficultyLevel() : 1)
                    .points(item.points())
                    .orderIndex(item.orderIndex())
                    .questionType(questionType)
                    .verifyScript(generated.verifyScript())
                    .build();

            questionsToSave.add(question);
        }

        List<ExamQuestion> saved = examQuestionRepository.saveAll(questionsToSave);
        return CreateExamQuestionsResponse.fromModels(saved);
    }

    private String buildSchemaContext(Long examId) {
        try {
            return examRepository.findById(examId)
                    .flatMap(exam -> examSpecificationRepository.findById(exam.getSpecificationId()))
                    .map(spec -> {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Database: ").append(spec.getName()).append("\n");
                        if (spec.getDescription() != null) {
                            sb.append(spec.getDescription()).append("\n\n");
                        }
                        if (spec.getEntities() != null) {
                            spec.getEntities().forEach(entity -> {
                                sb.append("Table ").append(entity.getEntityName())
                                        .append(" (").append(entity.getDisplayName()).append("):\n");
                                if (entity.getAttributes() != null) {
                                    entity.getAttributes().forEach(attr ->
                                            sb.append("  - ").append(attr.getAttributeName())
                                                    .append(" [").append(attr.getDataType()).append("]")
                                                    .append(attr.isPrimaryKey() ? " PRIMARY KEY" : "")
                                                    .append(!attr.isNullable() ? " NOT NULL" : "")
                                                    .append(attr.getDescription() != null
                                                            ? " -- " + attr.getDescription() : "")
                                                    .append("\n")
                                    );
                                }
                                if (entity.getDescription() != null) {
                                    sb.append("  Note: ").append(entity.getDescription()).append("\n");
                                }
                                sb.append("\n");
                            });
                        }
                        return sb.toString();
                    })
                    .orElse("No schema specification available");
        } catch (Exception e) {
            log.warn("Could not load schema context for exam {}: {}", examId, e.getMessage());
            return "No schema specification available";
        }
    }
}
