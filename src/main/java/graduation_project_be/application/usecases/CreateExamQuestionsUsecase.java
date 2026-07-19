package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.port.services.PdfStorageService;
import graduation_project_be.application.port.services.PdfTextExtractor;
import graduation_project_be.application.usecases.request.CreateExamQuestionsRequest;
import graduation_project_be.application.usecases.response.CreateExamQuestionsResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.TestCase;
import graduation_project_be.infrastructure.services.ExpectedValueDeriver;
import graduation_project_be.infrastructure.services.RubricToTestCaseTransformer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class CreateExamQuestionsUsecase {

    private final ClassRepository classRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final CurrentUserService currentUserService;
    private final AIService aiService;
    private final PdfStorageService pdfStorageService;
    private final PdfTextExtractor pdfTextExtractor;
    // T09: services for the rubric+test-case pipeline (SP/Function/Trigger only)
    private final RubricToTestCaseTransformer rubricTransformer;
    private final ExpectedValueDeriver expectedValueDeriver;

    @Transactional
    public CreateExamQuestionsResponse execute(CreateExamQuestionsRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id: " + request.examId()));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        if (!hasAccess) {
            throw new UnauthorizedException("You do not have access to this exam");
        }

        boolean requiresAi = request.questions().stream()
                .anyMatch(item -> item.correctQuery() == null || item.correctQuery().isBlank());
        String schemaContext = requiresAi
                ? buildGenerationContext(exam, request.schemaContext())
                : "";

        // Resolve spec once for the test-case pipeline below.
        ExamSpecification specification = exam.getSpecificationId() != null
                ? examSpecificationRepository.findById(exam.getSpecificationId()).orElse(null)
                : null;

        List<ExamQuestion> questionsToSave = new ArrayList<>();
        // Track the original input items aligned with questionsToSave so we can run
        // the post-save rubric pipeline only for SP/FN/Trigger types.
        List<CreateExamQuestionsRequest.QuestionItem> alignedItems = new ArrayList<>();

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

            String correctQuery = item.correctQuery();
            String verifyScript = item.verifyScript();

            boolean needsAi = (correctQuery == null || correctQuery.isBlank());

            if (needsAi) {
                log.info("Calling AIService.generateSqlAnswer for question: {}", item.content());
                AIService.GeneratedQuestion generated = aiService.generateSqlAnswer(
                        item.content(),
                        questionType.name(),
                        schemaContext);

                if (generated == null || generated.correctQuery() == null || generated.correctQuery().isBlank()) {
                    throw new BadRequestException(String.format(
                            "Câu %d: không thể sinh đáp án SQL. Vui lòng kiểm tra nội dung câu hỏi hoặc thử lại.",
                            item.orderIndex() != null ? item.orderIndex() : questionsToSave.size() + 1));
                }

                if (correctQuery == null || correctQuery.isBlank()) {
                    correctQuery = generated.correctQuery();
                }
                if (verifyScript == null || verifyScript.isBlank()) {
                    verifyScript = generated.verifyScript();
                }
            }

            ExamQuestion question = ExamQuestion.builder()
                    .examId(request.examId())
                    .content(item.content())
                    .correctQuery(correctQuery)
                    .difficultyLevel(item.difficultyLevel() != null ? item.difficultyLevel() : 1)
                    .points(item.points())
                    .orderIndex(item.orderIndex())
                    .questionType(questionType)
                    .verifyScript(verifyScript)
                    .gradingRubric(item.gradingRubric())
                    .build();

            questionsToSave.add(question);
            alignedItems.add(item);
        }

        List<ExamQuestion> saved = examQuestionRepository.saveAll(questionsToSave);

        // T09: post-save pipeline for SP/Function/Trigger.
        // Order:
        //   1. Generate rubric JSON via AIService if not provided.
        //   2. Parse → in-memory TestCase list (no expectedValue yet).
        //   3. Sandbox-derive expectedValue from teacher's correctQuery.
        //   4. Persist TestCase rows.
        // Failure at ANY step throws BadRequestException — the @Transactional
        // ensures the saved ExamQuestion rows roll back so the caller can fix the
        // issue (bad correctQuery / unworkable test case) and retry without
        // half-saved state.
        for (int i = 0; i < saved.size(); i++) {
            ExamQuestion q = saved.get(i);
            if (!isRoutineOrTriggerType(q.getQuestionType())) {
                continue;
            }

            String rubricJson = q.getGradingRubric();
            if (rubricJson == null || rubricJson.isBlank()) {
                rubricJson = generateRubricViaAi(q, specification);
                // Persist back so the rubric is visible from /generate-rubric callers
                // and from regrade flows.
                if (rubricJson != null) {
                    q.setGradingRubric(rubricJson);
                    examQuestionRepository.save(q);
                }
            }

            if (rubricJson == null || rubricJson.isBlank()) {
                throw new BadRequestException(String.format(
                        "Q%d (%s): không sinh được rubric tự động. Hãy thử lại hoặc cung cấp rubric thủ công.",
                        q.getOrderIndex(), q.getQuestionType()));
            }

            runRubricPipeline(q, rubricJson, specification);
        }

        return CreateExamQuestionsResponse.fromModels(saved);
    }

    private boolean isRoutineOrTriggerType(QuestionType type) {
        return type == QuestionType.STORED_PROCEDURE
                || type == QuestionType.FUNCTION
                || type == QuestionType.TRIGGER;
    }

    private String generateRubricViaAi(ExamQuestion q, ExamSpecification specification) {
        try {
            log.info("Calling AIService.generateGradingRubric for Q{} ({})", q.getId(), q.getQuestionType());
            return aiService.generateGradingRubric(
                    q.getCorrectQuery(),
                    q.getContent(),
                    q.getPoints() != null ? q.getPoints().doubleValue() : 0d,
                    q.getQuestionType().name(),
                    null,
                    specification != null ? specification.getDdlScript() : null);
        } catch (Exception e) {
            log.error("AIService.generateGradingRubric failed for Q{}: {}", q.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Runs Parse → Derive → Persist for one question. Throws BadRequestException
     * if the rubric is unparseable, if correctQuery cannot be applied, or if any
     * test case cannot be derived. The transaction will roll back the saved
     * ExamQuestion rows so the user gets a clean retry.
     */
    private void runRubricPipeline(ExamQuestion q, String rubricJson, ExamSpecification specification) {
        List<TestCase> testCases = rubricTransformer.parse(q.getId(), q.getQuestionType().name(), rubricJson);
        if (testCases.isEmpty()) {
            throw new BadRequestException(String.format(
                    "Q%d (%s): rubric không có test_cases hợp lệ. Vui lòng kiểm tra lại đề.",
                    q.getOrderIndex(), q.getQuestionType()));
        }

        String ddlScript = specification != null ? specification.getDdlScript() : null;
        ExpectedValueDeriver.DerivationResult result;
        try {
            result = expectedValueDeriver.derive(q.getId(), ddlScript, q.getCorrectQuery(), testCases);
        } catch (ExpectedValueDeriver.DerivationException e) {
            throw new BadRequestException(String.format(
                    "Q%d (%s): %s", q.getOrderIndex(), q.getQuestionType(), e.getMessage()));
        }

        if (!result.isFullySuccessful()) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Q%d (%s): có test case không derive được expected:\n",
                    q.getOrderIndex(), q.getQuestionType()));
            for (Map.Entry<Integer, String> entry : result.testCaseErrors().entrySet()) {
                sb.append("  - TC").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("Hãy điều chỉnh setup_script / invocation_query trong rubric và thử lại.");
            throw new BadRequestException(sb.toString());
        }

        rubricTransformer.persist(q.getId(), testCases);
        log.info("Q{} ({}) persisted {} test cases via T09 pipeline",
                q.getId(), q.getQuestionType(), testCases.size());
    }

    private String buildGenerationContext(Exam exam, String extractedSchemaContext) {
        String schemaContext = extractedSchemaContext != null && !extractedSchemaContext.isBlank()
                ? extractedSchemaContext.trim()
                : buildSchemaContext(exam);
        String pdfText = readFullPdfText(exam);

        StringBuilder context = new StringBuilder();
        if (!schemaContext.isBlank()) {
            context.append("=== SCHEMA TRÍCH XUẤT TỪ ĐỀ ===\n")
                    .append(schemaContext);
        }
        if (!pdfText.isBlank()) {
            if (!context.isEmpty()) {
                context.append("\n\n");
            }
            context.append("=== TOÀN BỘ NỘI DUNG FILE PDF ===\n")
                    .append(pdfText);
        }

        return context.isEmpty() ? "Không có thông tin schema hoặc nội dung PDF" : context.toString();
    }

    private String readFullPdfText(Exam exam) {
        if (exam.getPdfFilePath() == null || exam.getPdfFilePath().isBlank()) {
            return "";
        }

        try {
            byte[] pdfBytes = pdfStorageService.loadPdf(exam.getPdfFilePath());
            String pdfText = pdfTextExtractor.extract(pdfBytes);
            if (pdfText == null) {
                return "";
            }
            log.info("Loaded full PDF text for exam {} ({} chars)", exam.getId(), pdfText.length());
            return pdfText;
        } catch (Exception e) {
            log.warn("Could not read PDF text for exam {}: {}", exam.getId(), e.getMessage());
            return "";
        }
    }

    private String buildSchemaContext(Exam exam) {
        try {
            if (exam.getSpecificationId() == null) {
                return "";
            }
            return examSpecificationRepository.findById(exam.getSpecificationId())
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
                        // Append raw DDL so AI can read FOREIGN KEY constraints.
                        // SpecAttribute model intentionally does not carry FK metadata
                        // (only PK/nullable), so the table-by-table summary above lacks
                        // FK info — without DDL the AI cannot know e.g.
                        // ChuyenXe.TuyenXe REFERENCES TuyenXe(MaTuyen), and its
                        // setup_script will violate FK at runtime.
                        if (spec.getDdlScript() != null && !spec.getDdlScript().isBlank()) {
                            sb.append("\n--- RAW DDL (use this to identify FOREIGN KEY constraints) ---\n");
                            sb.append(spec.getDdlScript()).append("\n");
                        }
                        return sb.toString();
                    })
                    .orElse("");
        } catch (Exception e) {
            log.warn("Could not load schema context for exam {}: {}", exam.getId(), e.getMessage());
            return "";
        }
    }
}
