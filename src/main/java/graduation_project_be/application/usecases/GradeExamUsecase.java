package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.GradingTrace;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.GradingNotificationService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import graduation_project_be.application.usecases.grading.GradeDecision;
import graduation_project_be.application.usecases.grading.GradingSupport;
import graduation_project_be.application.usecases.grading.CreateTableQuestionGrader;
import graduation_project_be.application.usecases.grading.InsertDataQuestionGrader;
import graduation_project_be.application.usecases.grading.SelectQuestionGrader;
import graduation_project_be.application.usecases.grading.RoutineQuestionGrader;
import graduation_project_be.application.usecases.grading.TriggerQuestionGrader;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxEngine;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxResult;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Background grading usecase — extracted from the old synchronous
 * SubmitExamUsecase.
 * This usecase is invoked by the GradingWorker (not by a controller).
 * It grades all submissions for a specific exam + student + attempt,
 * then notifies the student via WebSocket.
 */
@Slf4j
@RequiredArgsConstructor
public class GradeExamUsecase {

    private final ExamRepository examRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final ClassRepository classRepository;
    private final ExamSchemaService examSchemaService;
    private final ExamSessionService examSessionService;
    private final GradingNotificationService gradingNotificationService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final GradingSupport support;
    private final CreateTableQuestionGrader createTableGrader;
    private final InsertDataQuestionGrader insertDataGrader;
    private final SelectQuestionGrader selectGrader;
    private final RoutineQuestionGrader routineGrader;
    private final TriggerQuestionGrader triggerGrader;
    private final WhiteboxEngine whiteboxEngine;

    /**
     * Applies white-box deduction on top of the black-box score. The engine emits its own trace; with
     * no {@code whitebox_rules} (or a zero deduction) the black-box decision is returned unchanged so
     * existing behaviour is preserved. A deduction that drops the score below max points makes the
     * answer no longer fully correct (resolved downstream from the final score).
     */
    private GradeDecision applyWhitebox(QuestionType questionType, ExamQuestion question,
                                        String studentQuery, GradeDecision blackbox) {
        BigDecimal points = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        WhiteboxResult whitebox = whiteboxEngine.evaluateFromPayload(
                questionType.name(), studentQuery, whiteboxPayload(question), points, true);
        if (whitebox.isEmpty() || whitebox.cappedDeduction().signum() <= 0) {
            return blackbox;
        }
        BigDecimal blackboxScore = blackbox.scoreEarned() == null ? BigDecimal.ZERO : blackbox.scoreEarned();
        BigDecimal finalScore = blackboxScore.subtract(whitebox.cappedDeduction()).setScale(2, RoundingMode.HALF_UP);
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

    private GradeDecision applySelectWhitebox(ExamQuestion question, String studentQuery, GradeDecision blackbox) {
        return applyWhitebox(QuestionType.SELECT_QUERY, question, studentQuery, blackbox);
    }

    /**
     * Applies FUNCTION/STORED_PROCEDURE white-box deduction on top of the black-box score.
     * Mirrors {@link #applyWhitebox} — the engine handles non-SELECT types via parseFailed() facts.
     */
    private GradeDecision applyRoutineWhitebox(QuestionType questionType, ExamQuestion question,
                                               String studentQuery, GradeDecision blackbox) {
        return applyWhitebox(questionType, question, studentQuery, blackbox);
    }

    /** Applies CREATE_TABLE method rules after the existing metadata/rubric black-box grader. */
    private GradeDecision applyCreateTableWhitebox(
            ExamQuestion question, String studentQuery, GradeDecision blackbox) {
        BigDecimal points = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        WhiteboxResult whitebox = whiteboxEngine.evaluateFromPayload(
                QuestionType.CREATE_TABLE.name(), studentQuery, whiteboxPayload(question), points, true);
        if (whitebox.isEmpty() || whitebox.cappedDeduction().signum() <= 0) {
            return blackbox;
        }
        BigDecimal blackboxScore = blackbox.scoreEarned() == null
                ? BigDecimal.ZERO
                : blackbox.scoreEarned();
        BigDecimal finalScore = blackboxScore.subtract(whitebox.cappedDeduction())
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        String message = blackbox.errorMessage() != null && !blackbox.errorMessage().isBlank()
                ? blackbox.errorMessage()
                : "Bị trừ " + whitebox.cappedDeduction().toPlainString()
                        + " điểm do vi phạm quy tắc whitebox CREATE TABLE.";
        return finalScore.compareTo(points) >= 0
                ? GradeDecision.pass(finalScore)
                : GradeDecision.partial(finalScore, message);
    }

    /** The {@code grading_payload} node of a question's rubric, or a missing node if unavailable. */
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

    @Transactional
    public void markSystemError(Long examId, Long studentId, int attemptNumber) {
        examResultRepository.findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber)
                .ifPresent(result -> {
                    result.setStatus(GradingStatus.SYSTEM_ERROR);
                    result.setLastGradedAt(TimeUtils.now());
                    examResultRepository.save(result);
                    log.error("Đã đánh dấu kết quả thi là SYSTEM_ERROR: exam={}, student={}, attempt={}", examId,
                            studentId, attemptNumber);
                });
    }

    /**
     * Loads DDL specification (and optionally the first active dataset) into a
     * schema. Used to reconstruct the same baseline state that the student saw
     * when starting the exam, so that grading questions which reference spec
     * tables (Function/SP/Trigger) work even if the exam has no CREATE_TABLE
     * questions.
     */
    private void setupSchemaWithSpec(
            String schemaName,
            ExamSpecification specification,
            boolean includeDataset,
            Long seedDatasetId) {
        if (specification == null || specification.getDdlScript() == null) {
            return;
        }
        String defaultDataScript = null;
        if (includeDataset && seedDatasetId != null && specification.getDatasets() != null) {
            defaultDataScript = specification.getDatasets().stream()
                    .filter(SpecDataset::isActive)
                    .filter(dataset -> seedDatasetId.equals(dataset.getId()))
                    .map(SpecDataset::getDataScript)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        examSchemaService.loadTemplateIntoSchema(schemaName, specification.getDdlScript(), defaultDataScript);
    }

    /**
     * Runs correctQuery of each non-SELECT question against the teacher schema in
     * orderIndex order. Returns a map of questionId -> error for any question
     * whose correctQuery failed to load. Those questions are marked with a clear
     * "[ĐỀ LỖI]" message during grading instead of silently failing the student.
     *
     * <p>Why per-question error capture instead of failing the whole grading job:
     * a single bad correctQuery (e.g. wrong column name in CREATE TRIGGER) should
     * NOT prevent grading of unrelated questions. Each failure is isolated.
     */
    private Map<Long, String> populateTeacherSchemaWithAnswers(String teacherSchemaName,
            List<ExamQuestion> sortedQuestions) {
        Map<Long, String> errors = new HashMap<>();
        for (ExamQuestion q : sortedQuestions) {
            // Skip types that are NOT applied as DDL on teacher schema:
            //  - SELECT_QUERY: it's a query, not a state-mutating DDL.
            //  - CREATE_TABLE / INSERT_DATA: the spec.ddlScript loaded earlier already
            //    contains the schema and seed data. Re-running these correctQuery
            //    would conflict ("object already exists" / PK violation) and falsely
            //    flag the question as broken. The metadata/data needed by SP/FN/
            //    Trigger questions comes from the spec, not from these Q's.
            QuestionType type = q.getQuestionType();
            if (type == QuestionType.SELECT_QUERY
                    || type == QuestionType.CREATE_TABLE
                    || type == QuestionType.INSERT_DATA) {
                continue;
            }
            String correctQuery = q.getCorrectQuery();
            if (correctQuery == null || correctQuery.isBlank()) {
                continue;
            }
            // Skip placeholder text from failed AI generation.
            String trimmed = correctQuery.trim();
            if (trimmed.startsWith("--") && !trimmed.contains("\n")) {
                log.warn("[TEACHER_SCHEMA] Câu {} có correctQuery giống giá trị tạm, bỏ qua: {}",
                        q.getId(), trimmed);
                continue;
            }
            try {
                support.executeSqlScriptBatches(teacherSchemaName, correctQuery);
                log.info("[TEACHER_SCHEMA] Đã áp dụng correctQuery cho câu {} ({})", q.getId(),
                        q.getQuestionType());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                errors.put(q.getId(), msg);
                log.error("[TEACHER_SCHEMA] Câu {} ({}) chạy correctQuery thất bại: {}",
                        q.getId(), q.getQuestionType(), msg);
            }
        }
        return errors;
    }

    @Transactional
    public void execute(Long examId, Long studentId, int attemptNumber) {
        log.info("Bắt đầu chấm bài: exam={}, student={}, attempt={}", examId, studentId, attemptNumber);

        // 1. Load exam
        Exam exam = examRepository.findByIdAndIsPublished(examId, true)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // 2. Update result status to GRADING
        ExamResult existingResult = examResultRepository
                .findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber)
                .orElseThrow(() -> new ResourceNotFoundException("ExamResult", "examId,studentId,attempt",
                        examId + "," + studentId + "," + attemptNumber));
        existingResult.setStatus(GradingStatus.GRADING);
        examResultRepository.save(existingResult);

        try {
            // 3. Load specification
            ExamSpecification specification = null;
            if (exam.getSpecificationId() != null) {
                specification = examSpecificationRepository.findById(exam.getSpecificationId()).orElse(null);
                if (specification == null) {
                    log.warn("Không tìm thấy đặc tả {} cho đề thi {}", exam.getSpecificationId(), examId);
                }
            }

            // 4. Load all questions for this exam
            List<ExamQuestion> allQuestions = examQuestionRepository.findByExamId(examId);

            BigDecimal maxScore = allQuestions.stream()
                    .map(ExamQuestion::getPoints)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int totalQuestions = allQuestions.size();

            // 5. Load student's submissions for this attempt
            List<ExamSubmission> submissions = examSubmissionRepository
                    .findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber);
            Map<Long, ExamSubmission> submissionByQuestionId = submissions.stream()
                    .collect(Collectors.toMap(ExamSubmission::getQuestionId, Function.identity()));

            String schemaName = String.format("exam_%d_student_%d_att_%d", examId, studentId, attemptNumber);
            String teacherSchemaName = schemaName + "_teacher";

            // 6. Sort questions once — used both to populate the teacher schema and
            // to grade student answers in deterministic order.
            List<ExamQuestion> sortedQuestions = allQuestions.stream()
                    .sorted(Comparator.comparingInt(ExamQuestion::getOrderIndex))
                    .toList();

            // 7. Reset + reload student schema with DDL spec only.
            // Without this, SP/Function/Trigger questions referencing tables from the spec
            // would fail because the schema was wiped clean before grading.
            // Seed dataset is only for the student's live exam environment; grading test
            // cases prepare their own data.
            boolean isLoadDdl = exam.getSettings() != null
                    && Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl());
            log.info("Đang reset schema [{}] trước khi chấm (isLoadDdl={})", schemaName, isLoadDdl);
            examSchemaService.resetSchema(schemaName, false);
            setupSchemaWithSpec(schemaName, specification, isLoadDdl, null);

            // 8. Setup teacher schema as a "reference answer" environment.
            // DDL only (no datasets — test cases provide their own setup data),
            // then run each question's correctQuery so that:
            //   - extractRoutineMetadata(teacher) returns the expected routines/triggers
            //   - SIDE_EFFECT/RESULT_SET test cases can derive expected_value by running
            //     their validation_query against the teacher's correct answer.
            log.info("Đang thiết lập schema giáo viên [{}] để kiểm tra test case", teacherSchemaName);
            examSchemaService.resetSchema(teacherSchemaName, false);
            setupSchemaWithSpec(teacherSchemaName, specification, false, null);
            Map<Long, String> teacherSetupErrors = populateTeacherSchemaWithAnswers(teacherSchemaName, sortedQuestions);

            // 9. Grade ALL questions sequentially (order by orderIndex)
            BigDecimal totalScore = BigDecimal.ZERO;
            int correctCount = 0;

            for (ExamQuestion question : sortedQuestions) {
                ExamSubmission submission = submissionByQuestionId.get(question.getId());
                String studentQuery = (submission != null) ? submission.getStudentQuery() : null;

                boolean isCorrect = false;
                String errorMessage = null;
                int executionTimeMs = 0;
                boolean hasExecutionError = false;

                try {
                    GradingTraceCollector.start();

                if (studentQuery == null || studentQuery.isBlank()) {
                    errorMessage = "Sinh viên chưa nộp câu trả lời.";
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_EXECUTION_ERROR, GradingTraceItem.STATUS_FAIL,
                            "Không nộp bài", "Sinh viên chưa nộp câu trả lời.",
                            null, null, null, null, null, null,
                            BigDecimal.ZERO, question.getPoints(), null, null, null, null));
                } else if (teacherSetupErrors.containsKey(question.getId())) {
                    // Teacher's correctQuery failed to load — this is a question-design bug,
                    // not the student's fault. Mark with a clear "[ĐỀ LỖI]" prefix so a
                    // teacher can spot it and override later. Keep score at 0 for now;
                    // a future task will add an AWAITING_TEACHER_REVIEW status.
                    errorMessage = "[ĐỀ LỖI] Đáp án mẫu của câu này không chạy được trên schema mẫu: "
                            + teacherSetupErrors.get(question.getId())
                            + ". Câu hỏi cần được giáo viên kiểm tra lại — điểm chấm tự động không tin cậy.";
                    log.warn("Bỏ qua chấm câu {} vì thiết lập đáp án giáo viên thất bại", question.getId());
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_EXECUTION_ERROR, GradingTraceItem.STATUS_FAIL,
                            "Lỗi đề bài", errorMessage,
                            null, null, null, null, null, null,
                            BigDecimal.ZERO, question.getPoints(), null, null, null,
                            "Đáp án giáo viên thất bại: " + teacherSetupErrors.get(question.getId())));
                    if (submission != null) {
                        submission.setScoreEarned(BigDecimal.ZERO);
                    }
                } else {
                    long startTime = System.currentTimeMillis();
                    try {
                        if (question.getQuestionType() == QuestionType.SELECT_QUERY) {
                            GradeDecision decision = selectGrader.hasSelectRubricTestCases(question)
                                    ? selectGrader.gradeSelectByRubricTestCases(
                                            exam,
                                            specification,
                                            sortedQuestions,
                                            schemaName,
                                            question,
                                            studentQuery)
                                    : selectGrader.gradeSelectAcrossDatasets(
                                            specification,
                                            schemaName,
                                            question,
                                            studentQuery);
                            // White-box method grading is applied after the black-box score; with no
                            // whitebox_rules configured the score is returned unchanged.
                            decision = applySelectWhitebox(question, studentQuery, decision);
                            isCorrect = decision.isCorrect();
                            errorMessage = decision.errorMessage();
                            if (submission != null) {
                                submission.setScoreEarned(decision.scoreEarned());
                            }
                        } else if (question.getQuestionType() == QuestionType.STORED_PROCEDURE) {
                            String routineSchemaName = schemaName + "_routine_" + question.getId();
                            boolean fallbackTriggered = false;
                            try {
                                examSchemaService.resetSchema(routineSchemaName, false);
                                setupSchemaWithSpec(routineSchemaName, specification, false, null);
                                try {
                                    support.executeSqlScriptBatches(routineSchemaName, studentQuery);
                                } catch (Exception execErr) {
                                    errorMessage = "Cảnh báo lỗi thực thi: " + execErr.getMessage();
                                    hasExecutionError = true;
                                    GradingTraceCollector.add(new GradingTraceItem(
                                            GradingTraceItem.KIND_EXECUTION_ERROR, GradingTraceItem.STATUS_FAIL,
                                            "Lỗi thực thi SQL", errorMessage,
                                            null, null, null, null, null, null,
                                            null, null, null, null, null, null));
                                }

                                if (hasExecutionError && support.isSyntaxErrorFailAllMode(question)) {
                                    if (submission != null) {
                                        submission.setScoreEarned(BigDecimal.ZERO);
                                        submission.setErrorMessage(
                                                "SQL lỗi thực thi và rubric đang để FAIL_ALL nên câu này bị 0 điểm.");
                                    }
                                    GradingTraceCollector.add(new GradingTraceItem(
                                            GradingTraceItem.KIND_TEACHER_CONFIG, GradingTraceItem.STATUS_FAIL,
                                            "FAIL_ALL kích hoạt", "SQL lỗi thực thi và rubric đang để FAIL_ALL nên câu này bị 0 điểm.",
                                            null, null, null, "SYNTAX_ERROR", "FAIL_ALL", null,
                                            BigDecimal.ZERO, question.getPoints(), question.getPoints(),
                                            null, null, "syntax_error_action=FAIL_ALL"));
                                    isCorrect = false;
                                } else if (submission != null) {
                                    isCorrect = gradeAnswer(routineSchemaName, teacherSchemaName, question, submission,
                                            fallbackTriggered);
                                }
                            } finally {
                                try {
                                    examSchemaService.dropSchema(routineSchemaName);
                                } catch (Exception e) {
                                    log.warn("Không thể xóa schema chấm routine [{}]: {}",
                                            routineSchemaName, e.getMessage());
                                }
                            }

                            if (submission != null && submission.getErrorMessage() != null
                                    && !submission.getErrorMessage().isBlank()) {
                                if (errorMessage != null) {
                                    errorMessage = errorMessage + " | Lỗi cú pháp/Cấu trúc: "
                                            + submission.getErrorMessage();
                                } else {
                                    errorMessage = submission.getErrorMessage();
                                }
                            } else if (!isCorrect && errorMessage == null) {
                                errorMessage = "Kết quả không khớp với đáp án mẫu.";
                            }
                            // Apply STORED_PROCEDURE white-box on top of black-box score
                            if (submission != null) {
                                BigDecimal spScore = submission.getScoreEarned() != null
                                        ? submission.getScoreEarned() : BigDecimal.ZERO;
                                GradeDecision spDecision = isCorrect
                                        ? GradeDecision.pass(spScore)
                                        : GradeDecision.partial(spScore, errorMessage);
                                spDecision = applyRoutineWhitebox(
                                        QuestionType.STORED_PROCEDURE, question, studentQuery, spDecision);
                                isCorrect = spDecision.isCorrect();
                                errorMessage = spDecision.errorMessage();
                                submission.setScoreEarned(spDecision.scoreEarned());
                            }
                        } else {
                            boolean fallbackTriggered = false;
                            try {
                                examSchemaService.executeSql(schemaName, studentQuery);
                            } catch (Exception execErr) {
                                String compileError = execErr.getMessage();
                                if (question.getQuestionType() == QuestionType.INSERT_DATA) {
                                    boolean fkError = compileError != null
                                            && (compileError.toLowerCase().contains("foreign key")
                                                    || compileError.toLowerCase().contains("ràng buộc")
                                                    || compileError.toLowerCase().contains("reference")
                                                    || compileError.toLowerCase().contains("conflict")
                                                    || compileError.toLowerCase().contains("khóa ngoại"));
                                    if (fkError) {
                                        fallbackTriggered = true;
                                        try {
                                            support.setAllConstraintsEnabled(schemaName, false);
                                        } catch (Exception ignore) {
                                        }
                                        try {
                                            examSchemaService.executeSql(schemaName, studentQuery);
                                        } catch (Exception retryErr) {
                                            errorMessage = "Lỗi Execute (sau khi tắt FK): " + retryErr.getMessage();
                                            hasExecutionError = true;
                                            GradingTraceCollector.add(new GradingTraceItem(
                                                    GradingTraceItem.KIND_EXECUTION_ERROR, GradingTraceItem.STATUS_FAIL,
                                                    "Lỗi FK (sau retry)", errorMessage,
                                                    null, null, null, null, null, null,
                                                    null, null, null, null, null, null));
                                        }
                                        try {
                                            support.setAllConstraintsEnabled(schemaName, true);
                                        } catch (Exception ignore) {
                                        }
                                    } else {
                                        errorMessage = "Cảnh báo Lỗi Execute: " + compileError;
                                        hasExecutionError = true;
                                        GradingTraceCollector.add(new GradingTraceItem(
                                                GradingTraceItem.KIND_EXECUTION_ERROR, GradingTraceItem.STATUS_FAIL,
                                                "Lỗi thực thi SQL", errorMessage,
                                                null, null, null, null, null, null,
                                                null, null, null, null, null, null));
                                    }
                                } else {
                                    errorMessage = "Cảnh báo Lỗi Execute: " + compileError;
                                    hasExecutionError = true;
                                    GradingTraceCollector.add(new GradingTraceItem(
                                            GradingTraceItem.KIND_EXECUTION_ERROR, GradingTraceItem.STATUS_FAIL,
                                            "Lỗi thực thi SQL", errorMessage,
                                            null, null, null, null, null, null,
                                            null, null, null, null, null, null));
                                }
                            }
                            if (hasExecutionError && support.isSyntaxErrorFailAllMode(question)) {
                                if (submission != null) {
                                    submission.setScoreEarned(BigDecimal.ZERO);
                                    submission.setErrorMessage(
                                            "SQL lỗi thực thi và rubric đang để FAIL_ALL nên câu này bị 0 điểm.");
                                }
                                GradingTraceCollector.add(new GradingTraceItem(
                                        GradingTraceItem.KIND_TEACHER_CONFIG, GradingTraceItem.STATUS_FAIL,
                                        "FAIL_ALL kích hoạt", "SQL lỗi thực thi và rubric đang để FAIL_ALL nên câu này bị 0 điểm.",
                                        null, null, null, "SYNTAX_ERROR", "FAIL_ALL", null,
                                        BigDecimal.ZERO, question.getPoints(), question.getPoints(),
                                        null, null, "syntax_error_action=FAIL_ALL"));
                                isCorrect = false;
                            } else if (submission != null) {
                                isCorrect = gradeAnswer(schemaName, teacherSchemaName, question, submission,
                                        fallbackTriggered);
                            }

                            if (submission != null && submission.getErrorMessage() != null
                                    && !submission.getErrorMessage().isBlank()) {
                                if (errorMessage != null) {
                                    errorMessage = errorMessage + " | Lỗi cú pháp/Cấu trúc: "
                                            + submission.getErrorMessage();
                                } else {
                                    errorMessage = submission.getErrorMessage();
                                }
                            } else if (!isCorrect && errorMessage == null) {
                                errorMessage = "Kết quả không khớp với đáp án mẫu.";
                            }
                            if (question.getQuestionType() == QuestionType.CREATE_TABLE && submission != null) {
                                BigDecimal createScore = submission.getScoreEarned() != null
                                        ? submission.getScoreEarned()
                                        : BigDecimal.ZERO;
                                GradeDecision createDecision = isCorrect
                                        ? GradeDecision.pass(createScore)
                                        : GradeDecision.partial(createScore, errorMessage);
                                createDecision = applyCreateTableWhitebox(question, studentQuery, createDecision);
                                isCorrect = createDecision.isCorrect();
                                errorMessage = createDecision.errorMessage();
                                submission.setScoreEarned(createDecision.scoreEarned());
                            }
                            // Apply method white-box on top of black-box score for supported script types.
                            if ((question.getQuestionType() == QuestionType.FUNCTION
                                    || question.getQuestionType() == QuestionType.INSERT_DATA
                                    || question.getQuestionType() == QuestionType.TRIGGER)
                                    && submission != null) {
                                BigDecimal currentScore = submission.getScoreEarned() != null
                                        ? submission.getScoreEarned() : BigDecimal.ZERO;
                                GradeDecision whiteboxDecision = isCorrect
                                        ? GradeDecision.pass(currentScore)
                                        : GradeDecision.partial(currentScore, errorMessage);
                                whiteboxDecision = applyWhitebox(
                                        question.getQuestionType(), question, studentQuery, whiteboxDecision);
                                isCorrect = whiteboxDecision.isCorrect();
                                errorMessage = whiteboxDecision.errorMessage();
                                submission.setScoreEarned(whiteboxDecision.scoreEarned());
                            }
                        }
                        executionTimeMs = (int) (System.currentTimeMillis() - startTime);
                    } catch (Exception e) {
                        executionTimeMs = (int) (System.currentTimeMillis() - startTime);
                        errorMessage = e.getMessage();
                        log.warn("Câu {} chạy thất bại: {}", question.getId(), e.getMessage());
                        GradingTraceCollector.add(new GradingTraceItem(
                                GradingTraceItem.KIND_EXECUTION_ERROR, GradingTraceItem.STATUS_FAIL,
                                "Lỗi ngoại lệ", errorMessage,
                                null, null, null, null, null, null,
                                null, null, null, null, null, null));
                    }
                }

                BigDecimal scoreEarned;
                if (question.getQuestionType() == QuestionType.CREATE_TABLE
                        || question.getQuestionType() == QuestionType.INSERT_DATA
                        || question.getQuestionType() == QuestionType.SELECT_QUERY
                        || question.getQuestionType() == QuestionType.STORED_PROCEDURE
                        || question.getQuestionType() == QuestionType.FUNCTION
                        || question.getQuestionType() == QuestionType.TRIGGER) {
                    // These types use algorithmic/partial grading — respect scoreEarned set by
                    // grader
                    scoreEarned = (submission != null && submission.getScoreEarned() != null)
                            ? submission.getScoreEarned()
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

                // Finalize trace and save submission
                List<GradingTraceItem> traceItems = GradingTraceCollector.finish();
                if (submission != null) {
                    submission.setIsCorrect(isCorrect);
                    submission.setScoreEarned(scoreEarned);
                    submission.setErrorMessage(errorMessage);
                    submission.setExecutionTimeMs(executionTimeMs);
                    submission.setStatus(SubmissionStatus.GRADED);
                    submission.setGradingTraceJson(serializeTrace(
                            traceItems, attemptNumber,
                            question.getGradingRubric()));
                    examSubmissionRepository.save(submission);
                }
                } finally {
                    // Ensure ThreadLocal is cleared even if anything above throws
                    // before the explicit finish() call. Safe to call twice — finish()
                    // already does ITEMS.remove(); isActive() will be false here on
                    // happy path so this is a no-op cleanup for that case.
                    if (GradingTraceCollector.isActive()) {
                        GradingTraceCollector.finish();
                    }
                }
            }

            // 8. Update ExamResult with final scores and COMPLETED status
            existingResult.setTotalScore(totalScore);
            existingResult.setMaxScore(maxScore);
            existingResult.setTotalQuestions(totalQuestions);
            existingResult.setCorrectCount(correctCount);
            existingResult.setStatus(GradingStatus.COMPLETED);
            existingResult.setLastGradedAt(TimeUtils.now());
            examResultRepository.save(existingResult);

            // Collect results as a generic structure for notification
            List<Map<String, Object>> resultsList = sortedQuestions.stream()
                    .map(q -> {
                        ExamSubmission s = submissionByQuestionId.get(q.getId());
                        Map<String, Object> map = new HashMap<>();
                        map.put("submissionId", s != null ? s.getId() : null);
                        map.put("questionId", q.getId());
                        map.put("orderIndex", q.getOrderIndex());
                        map.put("studentQuery", s != null ? s.getStudentQuery() : "");
                        map.put("isCorrect", s != null && Boolean.TRUE.equals(s.getIsCorrect()));
                        map.put("scoreEarned", s != null ? s.getScoreEarned() : BigDecimal.ZERO);
                        map.put("maxPoints", q.getPoints());
                        map.put("errorMessage", s != null ? s.getErrorMessage() : "No submission");
                        map.put("executionTimeMs", s != null ? s.getExecutionTimeMs() : null);
                        return map;
                    })
                    .collect(Collectors.toList());

            String questionResultsJson = "";
            try {
                questionResultsJson = objectMapper.writeValueAsString(resultsList);
            } catch (Exception e) {
                log.warn("Không thể tuần tự hóa kết quả câu hỏi sang JSON để gửi thông báo: {}", e.getMessage());
            }

            // 9. Cleanup — keep student schema for teacher review, only drop teacher schema
            // examSchemaService.dropSchema(schemaName); // REMOVED - teacher needs this schema
            try {
                examSchemaService.dropSchema(teacherSchemaName);
            } catch (Exception e) {
                log.warn("Không thể xóa schema [{}] sau khi chấm: {}", teacherSchemaName, e.getMessage());
            }

            // 10. End session — release Redis session lock
            try {
                examSessionService.endSession(examId, studentId);
            } catch (Exception e) {
                log.warn("Không thể kết thúc phiên thi exam={}, student={}: {}", examId, studentId, e.getMessage());
            }

            // 11. Notify student via WebSocket
            gradingNotificationService.notifyGradingCompleted(
                    examId, studentId, totalScore, maxScore, correctCount, totalQuestions,
                    questionResultsJson, TimeUtils.now());

            log.info("Chấm bài hoàn tất: exam={}, student={}, score={}/{}", examId, studentId, totalScore, maxScore);

            // 12. Notify teacher via WebSocket
            User student = userRepository.findById(studentId).orElse(null);
            String studentName = (student != null) ? student.getFullName() : "Không rõ";
            List<Long> teacherIds = resolveTeacherIds(exam);
            gradingNotificationService.notifyTeacherGradingCompleted(
                    examId, exam.getTitle(), teacherIds, studentId, studentName, totalScore, maxScore);

        } catch (Exception e) {
            if (isRetryableInfrastructureFailure(e)) {
                log.warn("Lỗi hạ tầng có thể retry khi chấm bài: exam={}, student={}, attempt={}: {}",
                        examId, studentId, attemptNumber, e.getMessage(), e);
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(e);
            }

            log.error("Chấm bài thất bại: exam={}, student={}, attempt={}: {}",
                    examId, studentId, attemptNumber, e.getMessage(), e);

            // Mark result as FAILED
            existingResult.setStatus(GradingStatus.FAILED);
            existingResult.setLastGradedAt(TimeUtils.now());
            examResultRepository.save(existingResult);

            // Notify student about failure
            gradingNotificationService.notifyGradingFailed(examId, studentId, e.getMessage());
        }
    }

    private boolean isRetryableInfrastructureFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String message = current.getMessage() == null
                    ? ""
                    : current.getMessage().toLowerCase(Locale.ROOT);
            if (className.contains("CannotAcquireLockException")
                    || className.contains("CannotCreateTransactionException")
                    || className.contains("CannotGetJdbcConnectionException")
                    || className.contains("DataAccessResourceFailureException")
                    || className.contains("TransientDataAccess")
                    || className.contains("SQLTransient")
                    || message.contains("deadlocked")
                    || message.contains("deadlock victim")
                    || message.contains("connection is not available")
                    || message.contains("could not obtain jdbc connection")
                    || message.contains("unable to acquire jdbc connection")
                    || message.contains("transport-level error")
                    || message.contains("connection reset")
                    || message.contains("connection is closed")
                    || message.contains("rerun the transaction")
                    || message.contains("lock request time out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<Long> resolveTeacherIds(Exam exam) {
        List<Long> teacherIds = new ArrayList<>(classRepository.findTeachersByClassId(exam.getClassId())
                .stream()
                .map(TeacherClass::getTeacherId)
                .toList());

        if (exam.getCreatorId() != null) {
            teacherIds.add(exam.getCreatorId());
        }

        return teacherIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private boolean gradeAnswer(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission, boolean fallbackTriggered) {
        QuestionType type = question.getQuestionType();
        switch (type) {
            case CREATE_TABLE:
                return createTableGrader.gradeCreateTableAlgorithmic(schemaName, teacherSchemaName, question, submission);
            case INSERT_DATA:
                return insertDataGrader.gradeInsertDataAlgorithmic(schemaName, teacherSchemaName, question, submission,
                        fallbackTriggered);
            case TRIGGER:
                return triggerGrader.gradeTriggerAlgorithmic(schemaName, teacherSchemaName, question, submission);
            case FUNCTION:
            case STORED_PROCEDURE:
                return routineGrader.gradeRoutineAlgorithmic(schemaName, teacherSchemaName, question, submission);
            default:
                log.warn("Loại câu hỏi không xác định: {}", type);
                return false;
        }
    }

    private String serializeTrace(List<GradingTraceItem> items, int attemptNumber, String rubricJson) {
        try {
            String rubricHash = rubricJson != null && !rubricJson.isBlank()
                    ? computeShortHash(rubricJson) : null;
            GradingTrace trace = new GradingTrace(
                    GradingTrace.CURRENT_SCHEMA_VERSION,
                    GradingTrace.CURRENT_RUN_VERSION,
                    TimeUtils.now(),
                    attemptNumber,
                    rubricHash,
                    items
            );
            return objectMapper.writeValueAsString(trace);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize grading trace: {}", e.getMessage());
            return null;
        }
    }

    private String computeShortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available in standard JRE — log if not.
            log.warn("SHA-256 algorithm unavailable for rubric hash: {}", e.getMessage());
            return null;
        }
    }
}
