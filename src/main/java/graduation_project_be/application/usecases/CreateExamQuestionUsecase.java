package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.usecases.request.CreateExamQuestionRequest;
import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.QuestionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class CreateExamQuestionUsecase {

    private final ClassRepository classRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final AIService aiService;

    public ExamQuestionResponse execute(CreateExamQuestionRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new IllegalArgumentException("Exam not found"));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        if (!hasAccess) {
            throw new UnauthorizedException("You do not have access to this exam");
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

        String correctQuery = request.correctQuery();
        String verifyScript = request.verifyScript();

        boolean needsAi = (correctQuery == null || correctQuery.isBlank());

        if (needsAi) {
            String schemaContext = buildSchemaContext(request.examId());
            log.info("Calling AIService.generateSqlAnswer for question: {}", request.content());
            AIService.GeneratedQuestion generated = aiService.generateSqlAnswer(
                    request.content(),
                    questionType.name(),
                    schemaContext);

            if (correctQuery == null || correctQuery.isBlank()) {
                correctQuery = generated.correctQuery();
            }
            if (verifyScript == null || verifyScript.isBlank()) {
                verifyScript = generated.verifyScript();
            }
        }

        ExamQuestion question = ExamQuestion.builder()
                .examId(request.examId())
                .content(request.content())
                .correctQuery(correctQuery)
                .difficultyLevel(request.difficultyLevel() != null ? request.difficultyLevel() : 1)
                .points(request.points())
                .orderIndex(request.orderIndex())
                .questionType(questionType)
                .verifyScript(verifyScript)
                .build();

        ExamQuestion saved = examQuestionRepository.save(question);
        return ExamQuestionResponse.fromModel(saved);
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
                        // Append raw DDL so AI can read FOREIGN KEY constraints
                        // (SpecAttribute does not carry FK metadata).
                        if (spec.getDdlScript() != null && !spec.getDdlScript().isBlank()) {
                            sb.append("\n--- RAW DDL (use this to identify FOREIGN KEY constraints) ---\n");
                            sb.append(spec.getDdlScript()).append("\n");
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
