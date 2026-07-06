package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.grading.CreateTableQuestionGrader;
import graduation_project_be.application.usecases.grading.GradeDecision;
import graduation_project_be.application.usecases.grading.GradingSupport;
import graduation_project_be.application.usecases.grading.InsertDataQuestionGrader;
import graduation_project_be.application.usecases.grading.RoutineQuestionGrader;
import graduation_project_be.application.usecases.grading.SelectQuestionGrader;
import graduation_project_be.application.usecases.grading.TriggerQuestionGrader;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxEngine;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxResult;
import graduation_project_be.application.usecases.request.PreviewSubmitRequest;
import graduation_project_be.application.usecases.response.SubmitExamResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.enums.GradingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class PreviewSubmitExamUsecase {

    private static final String TEACHER_SCHEMA_FORMAT = "exam_%d_teacher_%d";
    private static final String PREVIEW_GRADE_SCHEMA_FORMAT = "exam_%d_teacher_%d_preview_grade";

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final GradingSupport support;
    private final CreateTableQuestionGrader createTableGrader;
    private final InsertDataQuestionGrader insertDataGrader;
    private final SelectQuestionGrader selectGrader;
    private final RoutineQuestionGrader routineGrader;
    private final TriggerQuestionGrader triggerGrader;
    private final ObjectMapper objectMapper;
    private final WhiteboxEngine whiteboxEngine;

    private GradeDecision applyWhitebox(QuestionType questionType, ExamQuestion question,
                                        String studentQuery, GradeDecision blackbox) {
        BigDecimal points = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        WhiteboxResult whitebox = whiteboxEngine.evaluateFromPayload(
                questionType.name(), studentQuery, whiteboxPayload(question), points, false);
        if (whitebox.isEmpty() || whitebox.cappedDeduction().signum() <= 0) {
            return blackbox;
        }
        BigDecimal blackboxScore = blackbox.scoreEarned() == null ? BigDecimal.ZERO : blackbox.scoreEarned();
        BigDecimal finalScore = blackboxScore.subtract(whitebox.cappedDeduction())
                .setScale(2, RoundingMode.HALF_UP);
        if (finalScore.signum() < 0) {
            finalScore = BigDecimal.ZERO;
        }
        if (points.signum() > 0 && finalScore.compareTo(points) >= 0) {
            return GradeDecision.pass(finalScore);
        }
        String message = (blackbox.errorMessage() != null && !blackbox.errorMessage().isBlank())
                ? blackbox.errorMessage()
                : "Bị trừ " + whitebox.cappedDeduction().toPlainString()
                        + " điểm do vi phạm quy tắc whitebox (phương pháp viết câu lệnh).";
        return GradeDecision.partial(finalScore, message);
    }

    private JsonNode whiteboxPayload(ExamQuestion question) {
        if (question == null || question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(question.getGradingRubric()).path("grading_payload");
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    public SubmitExamResponse execute(PreviewSubmitRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();
        Long examId = request.examId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("Bạn không có quyền nộp bài xem thử cho đề thi này");
        }

        ExamSpecification specification = null;
        if (exam.getSpecificationId() != null) {
            specification = examSpecificationRepository.findById(exam.getSpecificationId()).orElse(null);
        }

        List<ExamQuestion> sortedQuestions = examQuestionRepository.findByExamId(examId).stream()
                .sorted(Comparator.comparingInt(ExamQuestion::getOrderIndex))
                .toList();

        Map<Long, String> answerMap = request.answers().stream()
                .collect(Collectors.toMap(
                        PreviewSubmitRequest.AnswerItem::questionId,
                        a -> a.studentQuery() != null ? a.studentQuery() : ""));

        BigDecimal maxScore = sortedQuestions.stream()
                .map(ExamQuestion::getPoints)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String schemaName = String.format(TEACHER_SCHEMA_FORMAT, examId, teacherId);
        String gradeSchemaName = String.format(PREVIEW_GRADE_SCHEMA_FORMAT, examId, teacherId);

        boolean isLoadDdl = exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl());

        // Reset preview schema with DDL only (no seed — graders provide their own test data)
        examSchemaService.resetSchema(schemaName, false);
        setupSchemaWithSpec(schemaName, specification, isLoadDdl, null);

        // Setup grading reference schema: DDL only + apply correctQuery for non-SELECT/CREATE/INSERT
        examSchemaService.resetSchema(gradeSchemaName, false);
        setupSchemaWithSpec(gradeSchemaName, specification, false, null);
        Map<Long, String> teacherSetupErrors = populateTeacherSchemaWithAnswers(gradeSchemaName, sortedQuestions);

        BigDecimal totalScore = BigDecimal.ZERO;
        int correctCount = 0;
        List<SubmitExamResponse.QuestionResultItem> questionResults = new ArrayList<>();

        for (ExamQuestion question : sortedQuestions) {
            String studentQuery = answerMap.getOrDefault(question.getId(), "");

            boolean isCorrect = false;
            String errorMessage = null;
            int executionTimeMs = 0;

            // In-memory submission — never saved to DB
            ExamSubmission submission = ExamSubmission.builder()
                    .questionId(question.getId())
                    .studentId(teacherId)
                    .studentQuery(studentQuery.isBlank() ? null : studentQuery)
                    .build();

            if (studentQuery.isBlank()) {
                errorMessage = "Chưa nộp câu trả lời.";
            } else if (teacherSetupErrors.containsKey(question.getId())) {
                errorMessage = "[ĐỀ LỖI] Đáp án mẫu không chạy được: "
                        + teacherSetupErrors.get(question.getId());
            } else {
                long startMs = System.currentTimeMillis();
                try {
                    if (question.getQuestionType() == QuestionType.SELECT_QUERY) {
                        GradeDecision decision = selectGrader.hasSelectRubricTestCases(question)
                                ? selectGrader.gradeSelectByRubricTestCases(
                                        exam, specification, sortedQuestions, schemaName, question, studentQuery)
                                : selectGrader.gradeSelectAcrossDatasets(
                                        specification, schemaName, question, studentQuery);
                        decision = applyWhitebox(QuestionType.SELECT_QUERY, question, studentQuery, decision);
                        isCorrect = decision.isCorrect();
                        errorMessage = decision.errorMessage();
                        submission.setScoreEarned(decision.scoreEarned());
                    } else if (question.getQuestionType() == QuestionType.STORED_PROCEDURE) {
                        String routineSchema = schemaName + "_pv_routine_" + question.getId();
                        try {
                            examSchemaService.resetSchema(routineSchema, false);
                            setupSchemaWithSpec(routineSchema, specification, false, null);
                            boolean hasExecError = false;
                            try {
                                support.executeSqlScriptBatches(routineSchema, studentQuery);
                            } catch (Exception execErr) {
                                errorMessage = "Cảnh báo lỗi thực thi: " + execErr.getMessage();
                                hasExecError = true;
                            }
                            if (!hasExecError || !support.isSyntaxErrorFailAllMode(question)) {
                                isCorrect = routineGrader.gradeRoutineAlgorithmic(
                                        routineSchema, gradeSchemaName, question, submission);
                                BigDecimal currentScore = submission.getScoreEarned() != null
                                        ? submission.getScoreEarned() : BigDecimal.ZERO;
                                GradeDecision decision = isCorrect
                                        ? GradeDecision.pass(currentScore)
                                        : GradeDecision.partial(currentScore, errorMessage);
                                decision = applyWhitebox(QuestionType.STORED_PROCEDURE, question, studentQuery,
                                        decision);
                                isCorrect = decision.isCorrect();
                                errorMessage = decision.errorMessage();
                                submission.setScoreEarned(decision.scoreEarned());
                            }
                        } finally {
                            try { examSchemaService.dropSchema(routineSchema); } catch (Exception ignore) {}
                        }
                    } else {
                        boolean hasExecError = false;
                        boolean fallbackTriggered = false;
                        try {
                            examSchemaService.executeSql(schemaName, studentQuery);
                        } catch (Exception execErr) {
                            String compileError = execErr.getMessage();
                            if (question.getQuestionType() == QuestionType.INSERT_DATA) {
                                boolean fkError = compileError != null
                                        && (compileError.toLowerCase().contains("foreign key")
                                                || compileError.toLowerCase().contains("reference")
                                                || compileError.toLowerCase().contains("conflict"));
                                if (fkError) {
                                    fallbackTriggered = true;
                                    try { support.setAllConstraintsEnabled(schemaName, false); } catch (Exception ignore) {}
                                    try {
                                        examSchemaService.executeSql(schemaName, studentQuery);
                                    } catch (Exception retryErr) {
                                        errorMessage = "Lỗi Execute (sau khi tắt FK): " + retryErr.getMessage();
                                        hasExecError = true;
                                    }
                                    try { support.setAllConstraintsEnabled(schemaName, true); } catch (Exception ignore) {}
                                } else {
                                    errorMessage = "Cảnh báo Lỗi Execute: " + compileError;
                                    hasExecError = true;
                                }
                            } else {
                                errorMessage = "Cảnh báo Lỗi Execute: " + compileError;
                                hasExecError = true;
                            }
                        }
                        if (!hasExecError || !support.isSyntaxErrorFailAllMode(question)) {
                            isCorrect = gradeAnswer(schemaName, gradeSchemaName, question, submission,
                                    fallbackTriggered);
                            if (submission.getErrorMessage() != null && !submission.getErrorMessage().isBlank()) {
                                errorMessage = errorMessage != null
                                        ? errorMessage + " | " + submission.getErrorMessage()
                                        : submission.getErrorMessage();
                            } else if (!isCorrect && errorMessage == null) {
                                errorMessage = "Kết quả không khớp với đáp án mẫu.";
                            }
                            if (question.getQuestionType() == QuestionType.FUNCTION
                                    || question.getQuestionType() == QuestionType.INSERT_DATA
                                    || question.getQuestionType() == QuestionType.TRIGGER) {
                                BigDecimal currentScore = submission.getScoreEarned() != null
                                        ? submission.getScoreEarned() : BigDecimal.ZERO;
                                GradeDecision decision = isCorrect
                                        ? GradeDecision.pass(currentScore)
                                        : GradeDecision.partial(currentScore, errorMessage);
                                decision = applyWhitebox(question.getQuestionType(), question, studentQuery, decision);
                                isCorrect = decision.isCorrect();
                                errorMessage = decision.errorMessage();
                                submission.setScoreEarned(decision.scoreEarned());
                            }
                        }
                    }
                    executionTimeMs = (int) (System.currentTimeMillis() - startMs);
                } catch (Exception e) {
                    executionTimeMs = (int) (System.currentTimeMillis() - startMs);
                    errorMessage = e.getMessage();
                    log.warn("[PREVIEW] Câu {} chấm thất bại: {}", question.getId(), e.getMessage());
                }
            }

            // Resolve final score (mirrors GradeExamUsecase logic)
            BigDecimal scoreEarned;
            QuestionType type = question.getQuestionType();
            if (type == QuestionType.CREATE_TABLE || type == QuestionType.INSERT_DATA
                    || type == QuestionType.SELECT_QUERY || type == QuestionType.STORED_PROCEDURE
                    || type == QuestionType.FUNCTION || type == QuestionType.TRIGGER) {
                scoreEarned = (submission.getScoreEarned() != null) ? submission.getScoreEarned()
                        : (isCorrect ? question.getPoints() : BigDecimal.ZERO);
            } else {
                scoreEarned = isCorrect ? question.getPoints() : BigDecimal.ZERO;
            }

            if (isCorrect || (scoreEarned.compareTo(BigDecimal.ZERO) > 0
                    && scoreEarned.compareTo(question.getPoints()) == 0)) {
                correctCount++;
                isCorrect = true;
            }
            totalScore = totalScore.add(scoreEarned);

            questionResults.add(new SubmitExamResponse.QuestionResultItem(
                    null,
                    question.getId(),
                    question.getOrderIndex(),
                    studentQuery,
                    isCorrect,
                    scoreEarned,
                    question.getPoints(),
                    errorMessage,
                    executionTimeMs
            ));
        }

        // Clean up temp grading schema
        try {
            examSchemaService.dropSchema(gradeSchemaName);
        } catch (Exception e) {
            log.warn("[PREVIEW] Không thể xóa schema chấm tạm [{}]: {}", gradeSchemaName, e.getMessage());
        }

        List<SubmitExamResponse.SubmissionDetail> details = sortedQuestions.stream()
                .map(q -> new SubmitExamResponse.SubmissionDetail(
                        q.getId(),
                        q.getContent(),
                        q.getPoints(),
                        answerMap.getOrDefault(q.getId(), "")))
                .toList();

        return new SubmitExamResponse(
                null,
                examId,
                teacherId,
                LocalDateTime.now(),
                GradingStatus.COMPLETED,
                totalScore,
                maxScore,
                correctCount,
                sortedQuestions.size(),
                details,
                questionResults
        );
    }

    private void setupSchemaWithSpec(String schemaName, ExamSpecification specification,
            boolean includeDataset, Long seedDatasetId) {
        if (specification == null || specification.getDdlScript() == null) return;
        String seedScript = null;
        if (includeDataset && seedDatasetId != null && specification.getDatasets() != null) {
            seedScript = specification.getDatasets().stream()
                    .filter(SpecDataset::isActive)
                    .filter(d -> seedDatasetId.equals(d.getId()))
                    .map(SpecDataset::getDataScript)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst().orElse(null);
        }
        examSchemaService.loadTemplateIntoSchema(schemaName, specification.getDdlScript(), seedScript);
    }

    private Map<Long, String> populateTeacherSchemaWithAnswers(String teacherSchema,
            List<ExamQuestion> sortedQuestions) {
        Map<Long, String> errors = new HashMap<>();
        for (ExamQuestion q : sortedQuestions) {
            QuestionType type = q.getQuestionType();
            if (type == QuestionType.SELECT_QUERY
                    || type == QuestionType.CREATE_TABLE
                    || type == QuestionType.INSERT_DATA) continue;
            String correctQuery = q.getCorrectQuery();
            if (correctQuery == null || correctQuery.isBlank()) continue;
            String trimmed = correctQuery.trim();
            if (trimmed.startsWith("--") && !trimmed.contains("\n")) continue;
            try {
                support.executeSqlScriptBatches(teacherSchema, correctQuery);
            } catch (Exception e) {
                errors.put(q.getId(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        return errors;
    }

    private boolean gradeAnswer(String schemaName, String teacherSchemaName,
            ExamQuestion question, ExamSubmission submission, boolean fallbackTriggered) {
        return switch (question.getQuestionType()) {
            case CREATE_TABLE -> createTableGrader.gradeCreateTableAlgorithmic(
                    schemaName, teacherSchemaName, question, submission);
            case INSERT_DATA -> insertDataGrader.gradeInsertDataAlgorithmic(
                    schemaName, teacherSchemaName, question, submission, fallbackTriggered);
            case TRIGGER -> triggerGrader.gradeTriggerAlgorithmic(
                    schemaName, teacherSchemaName, question, submission);
            case FUNCTION, STORED_PROCEDURE -> routineGrader.gradeRoutineAlgorithmic(
                    schemaName, teacherSchemaName, question, submission);
            default -> {
                log.warn("[PREVIEW] Loại câu hỏi không xác định: {}", question.getQuestionType());
                yield false;
            }
        };
    }
}
