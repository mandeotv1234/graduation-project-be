package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.GradingTrace;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TriggerMetadata;
import graduation_project_be.domain.models.TableMetadata.ColumnMetadata;
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
import graduation_project_be.domain.models.SqlExecutionResult;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import graduation_project_be.domain.models.enums.VerificationType;
import graduation_project_be.domain.models.TestCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final TestCaseRepository testCaseRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void markSystemError(Long examId, Long studentId, int attemptNumber) {
        examResultRepository.findByExamIdAndStudentIdAndAttemptNumber(examId, studentId, attemptNumber)
                .ifPresent(result -> {
                    result.setStatus(GradingStatus.SYSTEM_ERROR);
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
                executeSqlScriptBatches(teacherSchemaName, correctQuery);
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

    private void executeSqlScriptBatches(String schemaName, String sqlScript) {
        if (sqlScript == null || sqlScript.isBlank()) {
            return;
        }

        String normalized = sqlScript
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();

        for (String goBatch : normalized.split("(?im)^\\s*GO\\s*;?\\s*$")) {
            for (String batch : splitBatchBeforeCreateRoutine(goBatch)) {
                String executable = batch.trim();
                if (!executable.isBlank()) {
                    examSchemaService.executeSql(schemaName, normalizeDboReferences(executable, schemaName));
                }
            }
        }
    }

    private String normalizeDboReferences(String sql, String schemaName) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        return sql.replaceAll("(?i)\\bdbo\\s*\\.", "[" + schemaName + "].");
    }

    private List<String> splitBatchBeforeCreateRoutine(String batch) {
        if (batch == null || batch.isBlank()) {
            return List.of();
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)\\bCREATE\\s+(?:OR\\s+ALTER\\s+)?(?:PROCEDURE|PROC|FUNCTION|TRIGGER)\\b")
                .matcher(batch);
        if (!matcher.find()) {
            return List.of(batch);
        }

        String prefix = batch.substring(0, matcher.start()).trim();
        String routine = batch.substring(matcher.start()).trim();
        if (prefix.isBlank()) {
            return List.of(routine);
        }
        return List.of(prefix, routine);
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

            String schemaName = String.format("exam_%d_student_%d", examId, studentId);
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
                            GradeDecision decision = hasSelectRubricTestCases(question)
                                    ? gradeSelectByRubricTestCases(
                                            exam,
                                            specification,
                                            sortedQuestions,
                                            schemaName,
                                            question,
                                            studentQuery)
                                    : gradeSelectAcrossDatasets(
                                            specification,
                                            schemaName,
                                            question,
                                            studentQuery);
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
                                    executeSqlScriptBatches(routineSchemaName, studentQuery);
                                } catch (Exception execErr) {
                                    errorMessage = "Cảnh báo lỗi thực thi: " + execErr.getMessage();
                                    hasExecutionError = true;
                                    GradingTraceCollector.add(new GradingTraceItem(
                                            GradingTraceItem.KIND_EXECUTION_ERROR, GradingTraceItem.STATUS_FAIL,
                                            "Lỗi thực thi SQL", errorMessage,
                                            null, null, null, null, null, null,
                                            null, null, null, null, null, null));
                                }

                                if (hasExecutionError && isSyntaxErrorFailAllMode(question)) {
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
                                            setAllConstraintsEnabled(schemaName, false);
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
                                            setAllConstraintsEnabled(schemaName, true);
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
                            if (hasExecutionError && isSyntaxErrorFailAllMode(question)) {
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

            // 9. Cleanup — drop schemas after grading
            try {
                examSchemaService.dropSchema(schemaName);
                examSchemaService.dropSchema(teacherSchemaName);
            } catch (Exception e) {
                log.warn("Không thể xóa schema [{}] sau khi chấm: {}", schemaName, e.getMessage());
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
            log.error("Chấm bài thất bại: exam={}, student={}, attempt={}: {}",
                    examId, studentId, attemptNumber, e.getMessage(), e);

            // Mark result as FAILED
            existingResult.setStatus(GradingStatus.FAILED);
            examResultRepository.save(existingResult);

            // Notify student about failure
            gradingNotificationService.notifyGradingFailed(examId, studentId, e.getMessage());
        }
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

    // ========== Grading Logic (extracted from old SubmitExamUsecase) ==========

    private boolean gradeAnswer(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission, boolean fallbackTriggered) {
        QuestionType type = question.getQuestionType();
        switch (type) {
            case CREATE_TABLE:
                return gradeCreateTableAlgorithmic(schemaName, teacherSchemaName, question, submission);
            case INSERT_DATA:
                return gradeInsertDataAlgorithmic(schemaName, teacherSchemaName, question, submission,
                        fallbackTriggered);
            case TRIGGER:
                return gradeTriggerAlgorithmic(schemaName, teacherSchemaName, question, submission);
            case FUNCTION:
            case STORED_PROCEDURE:
                return gradeRoutineAlgorithmic(schemaName, teacherSchemaName, question, submission);
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
                    LocalDateTime.now(),
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

    private boolean gradeCreateTableAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission) {
        // === Check for rubric-based grading ===
        if (question.getGradingRubric() != null && !question.getGradingRubric().isBlank()) {
            try {
                return gradeCreateTableByRubricV2(schemaName, question, submission);
            } catch (Exception e) {
                log.warn("Chấm theo rubric thất bại cho câu {}, chuyển sang chấm thuật toán: {}", question.getId(),
                        e.getMessage());
            }
        }

        // === Fallback: existing algorithmic grading ===
        List<TableMetadata> expectedTables = examSchemaService.extractMetadata(teacherSchemaName);
        List<TableMetadata> actualTables = examSchemaService.extractMetadata(schemaName);

        if (expectedTables == null || expectedTables.isEmpty()) {
            return gradeByTestCases(schemaName, teacherSchemaName, question, submission);
        }

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        BigDecimal perTablePoints = totalPoints.divide(BigDecimal.valueOf(expectedTables.size()), 4,
                RoundingMode.HALF_UP);
        BigDecimal earnedTotal = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;

        for (TableMetadata expectedTable : expectedTables) {
            TableMetadata actualTable = actualTables.stream()
                    .filter(t -> t.getTableName().equalsIgnoreCase(expectedTable.getTableName()))
                    .findFirst().orElse(null);

            if (actualTable == null) {
                allPassed = false;
                errorBuilder.append(String.format("Thiếu bảng %s. ", expectedTable.getTableName()));
                continue;
            }

            int expectedColCount = expectedTable.getColumns().size();
            if (expectedColCount == 0) {
                earnedTotal = earnedTotal.add(perTablePoints);
                continue;
            }

            double tableScore = 0.0;
            double perColScore = 1.0 / expectedColCount;

            for (ColumnMetadata expectedCol : expectedTable.getColumns()) {
                ColumnMetadata actualCol = actualTable.getColumns().stream()
                        .filter(c -> c.getColumnName().equalsIgnoreCase(expectedCol.getColumnName()))
                        .findFirst().orElse(null);

                if (actualCol != null) {
                    double colPoints = 0.5 * perColScore;
                    if (actualCol.getDataType().equalsIgnoreCase(expectedCol.getDataType())) {
                        colPoints += 0.3 * perColScore;
                    } else {
                        errorBuilder
                                .append(String.format("Bảng %s: cột %s sai kiểu dữ liệu (Kỳ vọng: %s, Thực tế: %s). ",
                                        expectedTable.getTableName(), expectedCol.getColumnName(),
                                        expectedCol.getDataType(), actualCol.getDataType()));
                        allPassed = false;
                    }
                    if (actualCol.isPrimaryKey() == expectedCol.isPrimaryKey()) {
                        colPoints += 0.1 * perColScore;
                    } else {
                        if (expectedCol.isPrimaryKey()) {
                            errorBuilder.append(String.format("Bảng %s: thiếu khoá chính ở cột %s. ",
                                    expectedTable.getTableName(), expectedCol.getColumnName()));
                        } else {
                            errorBuilder.append(String.format("Bảng %s: dư định nghĩa khoá chính ở cột %s. ",
                                    expectedTable.getTableName(), expectedCol.getColumnName()));
                        }
                        allPassed = false;
                    }
                    if (expectedCol.isForeignKey()) {
                        if (actualCol.isForeignKey() &&
                                expectedCol.getReferencesTable().equalsIgnoreCase(actualCol.getReferencesTable())) {
                            colPoints += 0.1 * perColScore;
                        } else {
                            errorBuilder.append(String.format("Bảng %s, cột %s: thiếu khoá ngoại tham chiếu %s. ",
                                    expectedTable.getTableName(), expectedCol.getColumnName(),
                                    expectedCol.getReferencesTable()));
                            allPassed = false;
                        }
                    } else {
                        if (!actualCol.isForeignKey()) {
                            colPoints += 0.1 * perColScore;
                        } else {
                            errorBuilder.append(String.format("Bảng %s, cột %s: bị dư khoá ngoại sai đề. ",
                                    expectedTable.getTableName(), expectedCol.getColumnName()));
                            allPassed = false;
                        }
                    }
                    tableScore += colPoints;
                } else {
                    allPassed = false;
                    errorBuilder.append(String.format("Bảng %s: thiếu cột %s. ", expectedTable.getTableName(),
                            expectedCol.getColumnName()));
                }
            }
            BigDecimal tblEarned = perTablePoints.multiply(BigDecimal.valueOf(tableScore));
            earnedTotal = earnedTotal.add(tblEarned);
        }

        if (allPassed) {
            earnedTotal = totalPoints;
        } else if (earnedTotal.compareTo(totalPoints) > 0) {
            earnedTotal = totalPoints;
        }

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        if (GradingTraceCollector.isActive()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_SUMMARY,
                    allPassed ? GradingTraceItem.STATUS_PASS : GradingTraceItem.STATUS_FAIL,
                    "Kiểm tra cấu trúc bảng",
                    allPassed ? "Tất cả bảng và cột đúng cấu trúc" : errorBuilder.toString().trim(),
                    null, null, null, null, null, null,
                    earnedTotal, totalPoints, allPassed ? null : totalPoints.subtract(earnedTotal),
                    null, null,
                    "So sánh metadata bảng/cột/khóa giữa schema SV và schema GV"));
        }

        return allPassed;
    }

    private boolean isSyntaxErrorFailAllMode(ExamQuestion question) {
        if (question == null) {
            return false;
        }
        if (question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return false;
        }

        try {
            JsonNode root = objectMapper.readTree(question.getGradingRubric());
            String action = root
                    .path("grading_payload")
                    .path("grading_settings")
                    .path("syntax_error_action")
                    .asText("PARTIAL");
            return "FAIL_ALL".equalsIgnoreCase(action);
        } catch (Exception e) {
            log.warn("Không thể phân tích syntax_error_action cho câu {}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    private boolean gradeCreateTableByRubricV2(String schemaName, ExamQuestion question, ExamSubmission submission) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(question.getGradingRubric());
        } catch (Exception e) {
            throw new RuntimeException("Rubric JSON không hợp lệ: " + e.getMessage());
        }

        List<TableMetadata> actualTables = examSchemaService.extractMetadata(schemaName);
        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        CreateTableRubricEvaluator.CreateTableRubricGradeResult result = CreateTableRubricEvaluator.evaluate(rubric,
                actualTables, totalPoints);

        if (submission != null) {
            submission.setScoreEarned(result.earnedPoints());
            submission.setErrorMessage(result.errorMessage());
        }

        if (GradingTraceCollector.isActive()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_SUMMARY,
                    result.allPassed() ? GradingTraceItem.STATUS_PASS : GradingTraceItem.STATUS_FAIL,
                    "Kiểm tra CREATE TABLE (rubric)",
                    result.allPassed() ? "Tất cả kiểm tra rubric đạt"
                            : (result.errorMessage() != null ? result.errorMessage() : "Rubric kiểm tra thất bại"),
                    null, null, null, null, null, null,
                    result.earnedPoints(), totalPoints,
                    result.allPassed() ? null : totalPoints.subtract(result.earnedPoints()),
                    null, null,
                    "Chấm theo rubric CREATE TABLE"));

            if (result.details() != null) {
                for (Map<String, Object> detail : result.details()) {
                    String type = String.valueOf(detail.getOrDefault("type", "info"));
                    String msg = String.valueOf(detail.getOrDefault("message", ""));
                    Object pts = detail.get("points");
                    BigDecimal pointsVal = pts != null ? new BigDecimal(pts.toString()) : null;
                    boolean isError = "error".equals(type);

                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_RUBRIC_RULE,
                            isError ? GradingTraceItem.STATUS_FAIL : GradingTraceItem.STATUS_INFO,
                            msg.length() > 60 ? msg.substring(0, 57) + "..." : msg,
                            msg,
                            null, null,
                            null, null,
                            isError ? "DEDUCT_POINTS" : null,
                            isError && pointsVal != null ? pointsVal.abs() : null,
                            null, null,
                            isError && pointsVal != null ? pointsVal.abs() : null,
                            null, null,
                            "CREATE TABLE rubric rule"));
                }
            }
        }

        return result.allPassed();
    }

    private boolean gradeInsertDataAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission, boolean fallbackTriggered) {
        // === Check for rubric-based grading ===
        if (question.getGradingRubric() != null && !question.getGradingRubric().isBlank()) {
            try {
                return gradeInsertDataByRubric(schemaName, question, submission, fallbackTriggered);
            } catch (Exception e) {
                log.warn("Chấm theo rubric thất bại cho câu {}, chuyển sang chấm thuật toán cũ: {}",
                        question.getId(), e.getMessage());
            }
        }

        // === Fallback: existing algorithmic grading ===
        List<TableMetadata> expectedTables = examSchemaService.extractMetadata(teacherSchemaName);

        if (expectedTables == null || expectedTables.isEmpty()) {
            return gradeByTestCases(schemaName, teacherSchemaName, question, submission);
        }

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        BigDecimal perTablePoints = totalPoints.divide(BigDecimal.valueOf(expectedTables.size()), 4,
                RoundingMode.HALF_UP);
        BigDecimal earnedTotal = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;

        for (TableMetadata expectedTable : expectedTables) {
            String tName = expectedTable.getTableName();

            try {
                // 1. teacher count
                List<Map<String, Object>> tcRes = examSchemaService
                        .executeAdminSql("SELECT COUNT(*) as cnt FROM [" + teacherSchemaName + "]." + tName)
                        .getResultSet();
                long tCount = ((Number) tcRes.get(0).values().iterator().next()).longValue();

                if (tCount == 0) {
                    earnedTotal = earnedTotal.add(perTablePoints); // table not required to have data
                    continue;
                }

                // 2. student count
                long sCount = 0;
                try {
                    List<Map<String, Object>> scRes = examSchemaService
                            .executeAdminSql("SELECT COUNT(*) as cnt FROM [" + schemaName + "]." + tName)
                            .getResultSet();
                    sCount = ((Number) scRes.get(0).values().iterator().next()).longValue();
                } catch (Exception e) {
                    allPassed = false;
                    errorBuilder.append(String.format("Bảng %s lỗi trống rỗng hoặc chưa được tạo. ", tName));
                    continue; // 0 points for this table
                }

                // 3. missing count
                long missingCount = 0;
                try {
                    String missingSql = "SELECT COUNT(*) FROM (SELECT * FROM [" + teacherSchemaName + "]." + tName
                            + " EXCEPT SELECT * FROM [" + schemaName + "]." + tName + ") a";
                    List<Map<String, Object>> mRes = examSchemaService.executeAdminSql(missingSql).getResultSet();
                    missingCount = ((Number) mRes.get(0).values().iterator().next()).longValue();
                } catch (Exception e) {
                    missingCount = tCount; // fallback
                }

                // 4. extra count
                long extraCount = 0;
                try {
                    String extraSql = "SELECT COUNT(*) FROM (SELECT * FROM [" + schemaName + "]." + tName
                            + " EXCEPT SELECT * FROM [" + teacherSchemaName + "]." + tName + ") b";
                    List<Map<String, Object>> eRes = examSchemaService.executeAdminSql(extraSql).getResultSet();
                    extraCount = ((Number) eRes.get(0).values().iterator().next()).longValue();
                } catch (Exception e) {
                    extraCount = 0;
                }

                if (missingCount == 0 && extraCount == 0 && sCount == tCount) {
                    earnedTotal = earnedTotal.add(perTablePoints);
                } else {
                    allPassed = false;
                    long correctRows = Math.max(0, tCount - missingCount);
                    // Penalize extra wrong rows
                    long finalCorrect = Math.max(0, correctRows - extraCount);

                    double ratio = (double) finalCorrect / tCount;
                    earnedTotal = earnedTotal.add(perTablePoints.multiply(BigDecimal.valueOf(ratio)));
                    errorBuilder.append(
                            String.format("Bảng %s: thiếu %d dòng, dư/sai %d dòng. ", tName, missingCount, extraCount));
                }
            } catch (Exception e) {
                log.warn("Không thể chấm INSERT DATA bằng thuật toán cho bảng {}", tName, e);
                allPassed = false;
                errorBuilder.append(String.format("Lỗi hệ thống khi chấm bảng %s. ", tName));
            }
        }

        if (earnedTotal.compareTo(totalPoints) > 0)
            earnedTotal = totalPoints;

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        return allPassed;
    }

    public boolean gradeInsertDataByRubric(String schemaName, ExamQuestion question, ExamSubmission submission,
            boolean fallbackTriggered) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(question.getGradingRubric());
        } catch (Exception e) {
            throw new RuntimeException("Rubric JSON không hợp lệ: " + e.getMessage());
        }

        JsonNode payload = resolveInsertPayload(rubric);
        JsonNode settings = payload.path("grading_settings");
        JsonNode gradingRules = resolveInsertGradingRules(rubric, payload);
        boolean trimSpaces = readBooleanSetting(settings.path("trim_string_spaces"), true);
        boolean caseInsensitive = readBooleanSetting(settings.path("case_insensitive_data"), false);
        boolean hasLegacyExtraRowSettings = hasLegacyInsertExtraRowSettings(settings);
        boolean allowExtraRows = readBooleanSetting(settings.path("allow_extra_rows"), false);
        double penaltyPerExtraRow = Math.max(0d, readDoubleSetting(settings.path("penalty_per_extra_row"), 0.1d));
        boolean failAllMode = "FAIL_ALL".equalsIgnoreCase(settings.path("syntax_error_action").asText("PARTIAL"))
                || hasInsertFailAllRule(gradingRules);

        return gradeInsertDataByRubricDeduction(
                schemaName,
                question,
                submission,
                rubric,
                payload,
                gradingRules,
                trimSpaces,
                caseInsensitive,
                hasLegacyExtraRowSettings,
                allowExtraRows,
                penaltyPerExtraRow,
                failAllMode,
                fallbackTriggered);
    }

    private boolean gradeInsertDataByRubricDeduction(
            String schemaName,
            ExamQuestion question,
            ExamSubmission submission,
            JsonNode rubric,
            JsonNode payload,
            JsonNode gradingRules,
            boolean trimSpaces,
            boolean caseInsensitive,
            boolean hasLegacyExtraRowSettings,
            boolean allowExtraRows,
            double penaltyPerExtraRow,
            boolean failAllMode,
            boolean fallbackTriggered) {
        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        BigDecimal earnedTotal = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;
        boolean failAllTriggered = false;

        JsonNode datasets = resolveInsertDatasets(rubric, payload);
        int datasetCount = datasets.isArray() ? datasets.size() : 0;
        double defaultTablePoints = datasetCount > 0
                ? totalPoints.doubleValue() / datasetCount
                : 0d;

        JsonNode missingRowRule = findInsertRule(gradingRules, "ROW", "IS_MISSING");
        JsonNode extraRowRule = findInsertRule(gradingRules, "ROW", "IS_EXTRA");
        JsonNode cellNotEqualRule = findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
        JsonNode cellNullRule = findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
        JsonNode rowOrderRule = findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
        JsonNode fkRule = findInsertRule(gradingRules, "FOREIGN_KEY", "REFERENCE_ERROR");

        if (fallbackTriggered && fkRule != null && fkRule.isObject()) {
            InsertRuleDecision fkDecision = resolveInsertRuleDecision(fkRule, totalPoints.doubleValue(), 0d);
            if (!fkDecision.ignore()) {
                BigDecimal questionMaxPoints = totalPoints;
                addInsertRuleTrace(
                        "schema",
                        "FOREIGN_KEY",
                        "REFERENCE_ERROR",
                        1,
                        fkDecision,
                        0d,
                        questionMaxPoints,
                        "Vi phạm khóa ngoại/ràng buộc, hệ thống tự động chạy lại theo cấu hình rubric.");
                allPassed = false;
                if (fkDecision.failAll()) {
                    failAllTriggered = true;
                } else {
                    double deduction = fkDecision.penaltyPoints();
                    if (deduction > 0d) {
                        totalPoints = BigDecimal.valueOf(Math.max(0d, totalPoints.doubleValue() - deduction));
                        errorBuilder.append(String.format(
                                "Lỗi khóa ngoại (FK): vi phạm tham chiếu/ràng buộc, hệ thống tự động chạy lại (trừ %.2f điểm). ",
                                deduction));
                    }
                }
            }
        }

        JsonNode rowMatchModifiers = firstNonEmptyModifiers(
                extractInsertRuleModifiers(missingRowRule),
                extractInsertRuleModifiers(cellNotEqualRule));
        JsonNode cellCompareModifiers = extractInsertRuleModifiers(cellNotEqualRule);
        JsonNode cellNullModifiers = firstNonEmptyModifiers(
                extractInsertRuleModifiers(cellNullRule),
                cellCompareModifiers);

        for (int i = 0; i < datasets.size(); i++) {
            JsonNode dataset = datasets.get(i);
            String tableName = dataset.path("table_name").asText("").trim();
            if (tableName.isBlank()) {
                continue;
            }

            double tablePoints = Math.max(0d, dataset.path("table_points").asDouble(0d));
            if (tablePoints <= 0d) {
                tablePoints = Math.max(0d, defaultTablePoints);
            }

            JsonNode expectedRows = dataset.path("expected_data");
            if (expectedRows == null || expectedRows.isMissingNode() || !expectedRows.isArray()) {
                expectedRows = dataset.path("rows");
            }
            if (expectedRows == null || expectedRows.isMissingNode() || !expectedRows.isArray()
                    || expectedRows.size() == 0) {
                earnedTotal = earnedTotal.add(BigDecimal.valueOf(tablePoints));
                continue;
            }

            double rowPenalty = dataset.path("missing_row_penalty").asDouble(0d);
            if (rowPenalty <= 0d) {
                rowPenalty = dataset.path("points_per_row").asDouble(0d);
            }
            if (rowPenalty <= 0d) {
                Double rowRulePenalty = resolveInsertRulePenaltyPoints(
                        gradingRules,
                        "ROW",
                        "IS_MISSING",
                        tablePoints);
                if (rowRulePenalty != null && rowRulePenalty > 0d) {
                    rowPenalty = rowRulePenalty;
                }
            }
            if (rowPenalty <= 0d) {
                rowPenalty = tablePoints / Math.max(1, expectedRows.size());
            }
            rowPenalty = Math.max(0d, rowPenalty);

            boolean allOrNothing = "ALL_OR_NOTHING".equalsIgnoreCase(
                    dataset.path("row_grading_strategy").asText("PARTIAL_BY_COLUMN"));

            List<String> columnsToGrade = new ArrayList<>();
            List<String> primaryKeys = new ArrayList<>();
            Map<String, Double> columnPenalties = new HashMap<>();
            Map<String, String> columnMatchTypes = new HashMap<>();

            JsonNode columnsConfig = dataset.path("columns_config");
            if (columnsConfig.isArray()) {
                for (int c = 0; c < columnsConfig.size(); c++) {
                    JsonNode cc = columnsConfig.get(c);
                    String columnName = cc.path("name").asText("").trim();
                    if (columnName.isBlank()) {
                        continue;
                    }

                    if (cc.path("is_graded").asBoolean(true)) {
                        columnsToGrade.add(columnName);
                        columnPenalties.put(columnName, Math.max(0d, cc.path("points").asDouble(0d)));
                        columnMatchTypes.put(columnName, cc.path("match_type").asText("EXACT"));
                    }

                    if (cc.path("is_primary_key").asBoolean(false)) {
                        primaryKeys.add(columnName);
                    }
                }
            }

            if (primaryKeys.isEmpty()) {
                JsonNode pksNode = dataset.path("primary_keys");
                if (pksNode.isArray()) {
                    for (int p = 0; p < pksNode.size(); p++) {
                        String pk = pksNode.get(p).asText("").trim();
                        if (!pk.isBlank()) {
                            primaryKeys.add(pk);
                        }
                    }
                }
            }

            if (columnsToGrade.isEmpty()) {
                JsonNode columnsToGradeNode = dataset.path("columns_to_grade");
                if (columnsToGradeNode.isArray()) {
                    for (int c = 0; c < columnsToGradeNode.size(); c++) {
                        String col = columnsToGradeNode.get(c).asText("").trim();
                        if (!col.isBlank()) {
                            columnsToGrade.add(col);
                        }
                    }
                }
            }

            if (columnsToGrade.isEmpty() && expectedRows.size() > 0) {
                expectedRows.get(0).fieldNames().forEachRemaining(columnsToGrade::add);
            }

            if (columnsToGrade.isEmpty()) {
                earnedTotal = earnedTotal.add(BigDecimal.valueOf(tablePoints));
                continue;
            }

            double fallbackColPenalty = rowPenalty / Math.max(1, columnsToGrade.size());
            Double cellRulePenalty = resolveInsertRulePenaltyPoints(
                    gradingRules,
                    "CELL_VALUE",
                    "NOT_EQUAL",
                    tablePoints);
            if (cellRulePenalty != null && cellRulePenalty > 0d) {
                fallbackColPenalty = cellRulePenalty;
            }

            for (String col : columnsToGrade) {
                columnPenalties.putIfAbsent(col, fallbackColPenalty);
                columnMatchTypes.putIfAbsent(col, "EXACT");
            }

            List<Map<String, Object>> actualRows;
            try {
                actualRows = examSchemaService.executeAdminSql("SELECT * FROM [" + schemaName + "]." + tableName)
                        .getResultSet();
            } catch (Exception e) {
                allPassed = false;
                errorBuilder.append(String.format("Bảng %s bị lỗi hoặc không tồn tại. ", tableName));
                addInsertRuleTrace(
                        tableName,
                        "TABLE",
                        "IS_MISSING",
                        1,
                        new InsertRuleDecision("FAIL_ALL", tablePoints, false, true),
                        tablePoints,
                        BigDecimal.valueOf(tablePoints),
                        "Không đọc được bảng sinh viên: " + e.getMessage());
                continue;
            }

            double earnedTable = tablePoints;
            boolean[] usedActualRows = new boolean[actualRows.size()];
            List<Integer> matchedActualIndexes = new ArrayList<>();

            int missingRows = 0;
            int wrongCells = 0;
            int notEqualCells = 0;
            int nullCells = 0;
            int outOfOrderRows = 0;
            InsertRuleDecision missingDecisionForTrace = null;
            InsertRuleDecision cellNotEqualDecisionForTrace = null;
            InsertRuleDecision cellNullDecisionForTrace = null;
            InsertRuleDecision rowOrderDecisionForTrace = null;
            InsertRuleDecision extraDecisionForTrace = null;

            for (int r = 0; r < expectedRows.size(); r++) {
                JsonNode expectedRow = expectedRows.get(r);
                Map<String, Object> actualRow = null;
                int actualRowIdx = -1;

                for (int idx = 0; idx < actualRows.size(); idx++) {
                    if (usedActualRows[idx]) {
                        continue;
                    }

                    Map<String, Object> candidate = actualRows.get(idx);
                    boolean rowMatched;

                    if (!primaryKeys.isEmpty()) {
                        rowMatched = true;
                        for (String pk : primaryKeys) {
                            if (!valuesEqualByMatchTypeWithModifiers(
                                    getRowValueIgnoreCase(candidate, pk),
                                    getExpectedValueAsText(expectedRow, pk),
                                    "EXACT",
                                    rowMatchModifiers,
                                    trimSpaces,
                                    caseInsensitive)) {
                                rowMatched = false;
                                break;
                            }
                        }
                    } else {
                        rowMatched = false;
                        for (String col : columnsToGrade) {
                            String matchType = columnMatchTypes.getOrDefault(col, "EXACT");
                            if (valuesEqualByMatchTypeWithModifiers(
                                    getRowValueIgnoreCase(candidate, col),
                                    getExpectedValueAsText(expectedRow, col),
                                    matchType,
                                    rowMatchModifiers,
                                    trimSpaces,
                                    caseInsensitive)) {
                                rowMatched = true;
                                break;
                            }
                        }
                    }

                    if (rowMatched) {
                        actualRow = candidate;
                        actualRowIdx = idx;
                        break;
                    }
                }

                if (actualRow == null) {
                    InsertRuleDecision missingDecision = resolveInsertRuleDecision(missingRowRule, tablePoints,
                            rowPenalty);
                    if (!missingDecision.ignore()) {
                        missingDecisionForTrace = missingDecision;
                        allPassed = false;
                        missingRows++;
                        if (missingDecision.failAll()) {
                            failAllTriggered = true;
                            earnedTable = 0d;
                            break;
                        }
                        earnedTable = applyInsertPenalty(earnedTable, missingDecision.penaltyPoints(), 1);
                    }
                    continue;
                }

                usedActualRows[actualRowIdx] = true;
                matchedActualIndexes.add(actualRowIdx);

                double rowDeduction = 0d;
                boolean rowHasDeduction = false;
                boolean rowFailAll = false;

                for (String col : columnsToGrade) {
                    Object actualRawValue = getRowValueIgnoreCase(actualRow, col);
                    String expectedRawValue = getExpectedValueAsText(expectedRow, col);
                    String matchType = columnMatchTypes.getOrDefault(col, "EXACT");

                    boolean nullViolation = false;
                    if (cellNullRule != null) {
                        String normalizedActualForNull = applyInsertModifiers(
                                normalizeValueStr(actualRawValue, trimSpaces, caseInsensitive),
                                cellNullModifiers);
                        String normalizedExpectedForNull = applyInsertModifiers(
                                normalizeValueStr(expectedRawValue, trimSpaces, caseInsensitive),
                                cellNullModifiers);
                        nullViolation = !isNullLike(normalizedExpectedForNull) && isNullLike(normalizedActualForNull);
                    }

                    boolean isEqual = valuesEqualByMatchTypeWithModifiers(
                            actualRawValue,
                            expectedRawValue,
                            matchType,
                            cellCompareModifiers,
                            trimSpaces,
                            caseInsensitive);

                    if (!nullViolation && isEqual) {
                        continue;
                    }

                    JsonNode activeRule = nullViolation && cellNullRule != null
                            ? cellNullRule
                            : cellNotEqualRule;
                    double defaultPenalty = columnPenalties.getOrDefault(col, fallbackColPenalty);
                    InsertRuleDecision cellDecision = resolveInsertRuleDecision(activeRule, tablePoints,
                            defaultPenalty);

                    if (cellDecision.ignore()) {
                        continue;
                    }

                    allPassed = false;
                    wrongCells++;
                    if (nullViolation) {
                        nullCells++;
                        cellNullDecisionForTrace = cellDecision;
                    } else {
                        notEqualCells++;
                        cellNotEqualDecisionForTrace = cellDecision;
                    }

                    if (cellDecision.failAll()) {
                        rowFailAll = true;
                        break;
                    }

                    rowHasDeduction = true;
                    if (allOrNothing) {
                        rowDeduction = Math.max(rowDeduction, Math.max(rowPenalty, cellDecision.penaltyPoints()));
                    } else {
                        rowDeduction += cellDecision.penaltyPoints();
                    }
                }

                if (rowFailAll) {
                    failAllTriggered = true;
                    earnedTable = 0d;
                    break;
                }

                if (rowHasDeduction && rowDeduction > 0d) {
                    earnedTable = applyInsertPenalty(earnedTable, rowDeduction, 1);
                }
            }

            if (earnedTable > 0d && rowOrderRule != null && !hasInsertModifier(rowOrderRule, "SORT_ASC")) {
                outOfOrderRows = countInsertOutOfOrderViolations(matchedActualIndexes);
                if (outOfOrderRows > 0) {
                    InsertRuleDecision rowOrderDecision = resolveInsertRuleDecision(rowOrderRule, tablePoints,
                            rowPenalty);
                    if (!rowOrderDecision.ignore()) {
                        rowOrderDecisionForTrace = rowOrderDecision;
                        allPassed = false;
                        if (rowOrderDecision.failAll()) {
                            failAllTriggered = true;
                            earnedTable = 0d;
                        } else {
                            earnedTable = applyInsertPenalty(earnedTable, rowOrderDecision.penaltyPoints(),
                                    outOfOrderRows);
                        }
                    }
                }
            }

            int extraRows = 0;
            for (boolean used : usedActualRows) {
                if (!used) {
                    extraRows++;
                }
            }

            if (earnedTable > 0d && extraRows > 0) {
                if (!hasLegacyExtraRowSettings && extraRowRule != null && extraRowRule.isObject()) {
                    double defaultExtraPenalty = Math.max(rowPenalty, tablePoints * penaltyPerExtraRow);
                    InsertRuleDecision extraDecision = resolveInsertRuleDecision(extraRowRule, tablePoints,
                            defaultExtraPenalty);

                    if (!extraDecision.ignore()) {
                        extraDecisionForTrace = extraDecision;
                        allPassed = false;
                        if (extraDecision.failAll()) {
                            failAllTriggered = true;
                            earnedTable = 0d;
                        } else {
                            earnedTable = applyInsertPenalty(earnedTable, extraDecision.penaltyPoints(), extraRows);
                        }
                    }
                } else {
                    allPassed = false;
                    if (allowExtraRows) {
                        double penaltyPerExtra = tablePoints * penaltyPerExtraRow;
                        extraDecisionForTrace = new InsertRuleDecision("DEDUCT_POINTS", penaltyPerExtra, false, false);
                        earnedTable = applyInsertPenalty(earnedTable, penaltyPerExtra * extraRows, 1);
                    } else {
                        extraDecisionForTrace = new InsertRuleDecision("FAIL_ALL", tablePoints, false, true);
                        earnedTable = 0d;
                    }
                }
            }

            addInsertRuleTrace(
                    tableName,
                    "ROW",
                    "IS_MISSING",
                    missingRows,
                    missingDecisionForTrace,
                    rowPenalty,
                    BigDecimal.valueOf(tablePoints),
                    null);
            addInsertRuleTrace(
                    tableName,
                    "CELL_VALUE",
                    "NOT_EQUAL",
                    notEqualCells,
                    cellNotEqualDecisionForTrace,
                    fallbackColPenalty,
                    BigDecimal.valueOf(tablePoints),
                    null);
            addInsertRuleTrace(
                    tableName,
                    "CELL_VALUE",
                    "IS_NULL",
                    nullCells,
                    cellNullDecisionForTrace,
                    fallbackColPenalty,
                    BigDecimal.valueOf(tablePoints),
                    null);
            addInsertRuleTrace(
                    tableName,
                    "ROW_ORDER",
                    "OUT_OF_ORDER",
                    outOfOrderRows,
                    rowOrderDecisionForTrace,
                    rowPenalty,
                    BigDecimal.valueOf(tablePoints),
                    null);
            addInsertRuleTrace(
                    tableName,
                    "ROW",
                    "IS_EXTRA",
                    extraRows,
                    extraDecisionForTrace,
                    Math.max(rowPenalty, tablePoints * penaltyPerExtraRow),
                    BigDecimal.valueOf(tablePoints),
                    null);

            if (missingRows > 0 || wrongCells > 0 || extraRows > 0 || outOfOrderRows > 0) {
                double tableDeduction = Math.max(0d, tablePoints - Math.max(0d, earnedTable));
                errorBuilder.append(String.format(
                        Locale.ROOT,
                        "Bảng %s: thiếu %d dòng, sai %d ô, dư %d dòng, sai thứ tự %d dòng, trừ %.2f điểm. ",
                        tableName,
                        missingRows,
                        wrongCells,
                        extraRows,
                        outOfOrderRows,
                        tableDeduction));
            }

            earnedTotal = earnedTotal.add(BigDecimal.valueOf(Math.max(0d, earnedTable)));
        }

        earnedTotal = earnedTotal.setScale(2, RoundingMode.HALF_UP);
        if ((failAllMode && !allPassed) || failAllTriggered) {
            earnedTotal = BigDecimal.ZERO;
            if (errorBuilder.length() > 0) {
                errorBuilder.insert(0, "Rubric đang để FAIL_ALL: có lỗi nên câu này bị 0 điểm toàn bộ. ");
            } else {
                errorBuilder.append("Rubric đang để FAIL_ALL: có lỗi nên câu này bị 0 điểm toàn bộ.");
            }
        }

        if (earnedTotal.compareTo(totalPoints) > 0)
            earnedTotal = totalPoints;
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0)
            earnedTotal = BigDecimal.ZERO;

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed || failAllTriggered) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        boolean insertPassed = allPassed && !failAllTriggered;
        if (GradingTraceCollector.isActive()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_SUMMARY,
                    insertPassed ? GradingTraceItem.STATUS_PASS : GradingTraceItem.STATUS_FAIL,
                    "Kiểm tra INSERT DATA",
                    insertPassed ? "Tất cả dữ liệu khớp"
                            : (errorBuilder.length() > 0 ? errorBuilder.toString().trim() : "Dữ liệu không khớp"),
                    null, null, null, null, null, null,
                    earnedTotal, totalPoints,
                    insertPassed ? null : totalPoints.subtract(earnedTotal),
                    null, null,
                    "So sánh dữ liệu INSERT giữa schema SV và GV"));

        }

        return insertPassed;
    }

    private record InsertRuleDecision(String action, double penaltyPoints, boolean ignore, boolean failAll) {
    }

    private void addInsertRuleTrace(
            String tableName,
            String target,
            String condition,
            int violationCount,
            InsertRuleDecision decision,
            double defaultPenaltyPoints,
            BigDecimal tableMaxPoints,
            String overrideMessage) {
        if (!GradingTraceCollector.isActive() || violationCount <= 0 || decision == null || decision.ignore()) {
            return;
        }

        BigDecimal safeMaxPoints = tableMaxPoints != null ? tableMaxPoints : BigDecimal.ZERO;
        BigDecimal configuredPenalty = BigDecimal.valueOf(Math.max(0d,
                decision.penaltyPoints() > 0d ? decision.penaltyPoints() : defaultPenaltyPoints))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deductedPoints = decision.failAll()
                ? safeMaxPoints
                : configuredPenalty.multiply(BigDecimal.valueOf(violationCount));
        if (deductedPoints.compareTo(safeMaxPoints) > 0) {
            deductedPoints = safeMaxPoints;
        }

        String ruleLabel = target + "/" + condition;
        String message = overrideMessage != null && !overrideMessage.isBlank()
                ? overrideMessage
                : String.format(
                        Locale.ROOT,
                        "Bảng %s có %d vi phạm %s, action=%s.",
                        tableName,
                        violationCount,
                        ruleLabel,
                        decision.action());

        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_RUBRIC_RULE,
                GradingTraceItem.STATUS_FAIL,
                "Bảng " + tableName + ": " + ruleLabel,
                message,
                null,
                null,
                target,
                condition,
                decision.action(),
                configuredPenalty,
                null,
                safeMaxPoints,
                deductedPoints.setScale(2, RoundingMode.HALF_UP),
                null,
                null,
                "INSERT DATA rubric rule"));
    }

    private InsertRuleDecision resolveInsertRuleDecision(JsonNode ruleNode, double tablePoints,
            double defaultPenaltyPoints) {
        String action = ruleNode != null ? ruleNode.path("action").asText("").trim() : "";
        if (action.isBlank()) {
            action = "DEDUCT_POINTS";
        }

        String normalizedAction = action.toUpperCase(Locale.ROOT);
        double safeDefaultPenalty = Math.max(0d, defaultPenaltyPoints);
        double penaltyValue = ruleNode != null
                ? readDoubleSetting(ruleNode.path("penalty_value"), -1d)
                : -1d;

        switch (normalizedAction) {
            case "IGNORE":
                return new InsertRuleDecision(normalizedAction, 0d, true, false);
            case "FAIL_ALL":
                return new InsertRuleDecision(normalizedAction, Math.max(0d, tablePoints), false, true);
            case "FAIL_ITEM":
                return new InsertRuleDecision(normalizedAction, safeDefaultPenalty, false, false);
            case "DEDUCT_PERCENTAGE": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, tablePoints * penaltyValue / 100d)
                        : safeDefaultPenalty;
                return new InsertRuleDecision(normalizedAction, penalty, false, false);
            }
            case "DEDUCT_POINTS": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, penaltyValue)
                        : safeDefaultPenalty;
                return new InsertRuleDecision(normalizedAction, penalty, false, false);
            }
            default:
                return new InsertRuleDecision("DEDUCT_POINTS", safeDefaultPenalty, false, false);
        }
    }

    private double applyInsertPenalty(double earnedTable, double penaltyPoints, int violationCount) {
        if (violationCount <= 0 || penaltyPoints <= 0d) {
            return earnedTable;
        }
        return Math.max(0d, earnedTable - (penaltyPoints * violationCount));
    }

    private JsonNode extractInsertRuleModifiers(JsonNode ruleNode) {
        if (ruleNode == null || !ruleNode.isObject()) {
            return objectMapper.createArrayNode();
        }

        JsonNode modifiers = ruleNode.path("modifiers");
        return modifiers.isArray() ? modifiers : objectMapper.createArrayNode();
    }

    private JsonNode firstNonEmptyModifiers(JsonNode primary, JsonNode fallback) {
        if (primary != null && primary.isArray() && primary.size() > 0) {
            return primary;
        }
        if (fallback != null && fallback.isArray() && fallback.size() > 0) {
            return fallback;
        }
        return objectMapper.createArrayNode();
    }

    private boolean hasInsertModifier(JsonNode ruleNode, String expectedModifier) {
        if (expectedModifier == null || expectedModifier.isBlank()) {
            return false;
        }

        JsonNode modifiers = extractInsertRuleModifiers(ruleNode);
        for (JsonNode modifierNode : modifiers) {
            if (expectedModifier.equalsIgnoreCase(modifierNode.asText(""))) {
                return true;
            }
        }

        return false;
    }

    private boolean valuesEqualByMatchTypeWithModifiers(
            Object actualValue,
            Object expectedValue,
            String matchType,
            JsonNode modifiers,
            boolean trimSpaces,
            boolean caseInsensitive) {
        String normalizedActual = normalizeValueStr(actualValue, trimSpaces, caseInsensitive);
        String normalizedExpected = normalizeValueStr(expectedValue, trimSpaces, caseInsensitive);

        String modifiedActual = applyInsertModifiers(normalizedActual, modifiers);
        String modifiedExpected = applyInsertModifiers(normalizedExpected, modifiers);

        return valuesEqualByMatchType(modifiedActual, modifiedExpected, matchType);
    }

    private String applyInsertModifiers(String value, JsonNode modifiers) {
        if (value == null) {
            return null;
        }

        if (modifiers == null || !modifiers.isArray() || modifiers.isEmpty()) {
            return value;
        }

        String current = value;
        for (JsonNode modifierNode : modifiers) {
            String modifier = modifierNode.asText("").trim().toUpperCase(Locale.ROOT);
            if (modifier.isBlank()) {
                continue;
            }

            switch (modifier) {
                case "TO_LOWERCASE":
                    current = current.toLowerCase(Locale.ROOT);
                    break;
                case "TRIM_WHITESPACE":
                    current = current.trim();
                    break;
                case "REMOVE_ALL_WHITESPACE":
                    current = current.replaceAll("\\s+", "");
                    break;
                case "REMOVE_DIACRITICS": {
                    String normalized = Normalizer.normalize(current, Normalizer.Form.NFD);
                    current = normalized.replaceAll("\\p{M}+", "");
                    break;
                }
                case "REMOVE_SPECIAL_CHARS":
                    current = current.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", "");
                    break;
                case "CAST_TO_STRING":
                    current = String.valueOf(current);
                    break;
                case "CAST_TO_FLOAT":
                    current = canonicalizeNumber(current, -1);
                    break;
                case "ROUND_TO_INT":
                    current = canonicalizeNumber(current, 0);
                    break;
                case "ROUND_2_DECIMALS":
                    current = canonicalizeNumber(current, 2);
                    break;
                case "SORT_ASC":
                    current = sortTokensAscending(current);
                    break;
                default:
                    break;
            }
        }

        return current;
    }

    private String sortTokensAscending(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return trimmed;
        }

        boolean commaSeparated = trimmed.contains(",");
        String[] rawTokens = commaSeparated
                ? trimmed.split("\\s*,\\s*")
                : trimmed.split("\\s+");
        if (rawTokens.length <= 1) {
            return trimmed;
        }

        List<String> tokens = new ArrayList<>();
        for (String rawToken : rawTokens) {
            if (rawToken == null) {
                continue;
            }
            String token = rawToken.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }

        if (tokens.size() <= 1) {
            return trimmed;
        }

        tokens.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(commaSeparated ? "," : " ", tokens);
    }

    private String canonicalizeNumber(String value, int targetScale) {
        if (value == null) {
            return null;
        }

        BigDecimal decimal = parseDecimal(value.trim());
        if (decimal == null) {
            return value;
        }

        if (targetScale >= 0) {
            decimal = decimal.setScale(targetScale, RoundingMode.HALF_UP);
        }

        return decimal.stripTrailingZeros().toPlainString();
    }

    private boolean isNullLike(String val) {
        return val == null || val.isEmpty() || "null".equalsIgnoreCase(val);
    }

    private int countInsertOutOfOrderViolations(List<Integer> matchedActualIndexes) {
        if (matchedActualIndexes == null || matchedActualIndexes.size() <= 1) {
            return 0;
        }

        int maxSeen = -1;
        int violations = 0;
        for (Integer actualIdx : matchedActualIndexes) {
            if (actualIdx == null || actualIdx < 0) {
                continue;
            }

            if (actualIdx < maxSeen) {
                violations++;
            } else {
                maxSeen = actualIdx;
            }
        }

        return violations;
    }

    private String normalizeValueStr(Object val, boolean trimSpaces, boolean caseInsensitive) {
        if (val == null)
            return null;
        String s = String.valueOf(val);
        if (trimSpaces)
            s = s.trim();
        if (caseInsensitive)
            s = s.toLowerCase();
        return s;
    }

    private Object getRowValueIgnoreCase(Map<String, Object> row, String columnName) {
        if (row == null || columnName == null) {
            return null;
        }

        if (row.containsKey(columnName)) {
            return row.get(columnName);
        }

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private String getExpectedValueAsText(JsonNode expectedRow, String columnName) {
        if (expectedRow == null || columnName == null || !expectedRow.isObject()) {
            return null;
        }

        JsonNode direct = expectedRow.get(columnName);
        if (direct != null) {
            return direct.isNull() ? null : direct.asText();
        }

        Iterator<String> fields = expectedRow.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (field != null && field.equalsIgnoreCase(columnName)) {
                JsonNode value = expectedRow.get(field);
                return value == null || value.isNull() ? null : value.asText();
            }
        }

        return null;
    }

    private boolean valuesEqual(String actual, String expected) {
        if (java.util.Objects.equals(actual, expected)) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }

        BigDecimal aNum = parseDecimal(actual);
        BigDecimal eNum = parseDecimal(expected);
        if (aNum != null && eNum != null) {
            return aNum.compareTo(eNum) == 0;
        }

        return false;
    }

    private boolean valuesEqualByMatchType(String actual, String expected, String matchType) {
        String normalizedMatchType = matchType == null ? "EXACT" : matchType.trim().toUpperCase(Locale.ROOT);
        switch (normalizedMatchType) {
            case "IGNORE_CASE_AND_SPACE":
                String actualIgnoreCase = normalizeValueStr(actual, true, true);
                String expectedIgnoreCase = normalizeValueStr(expected, true, true);
                return valuesEqual(actualIgnoreCase, expectedIgnoreCase);
            case "NUMERIC_TOLERANCE":
                BigDecimal aNum = parseDecimal(actual);
                BigDecimal eNum = parseDecimal(expected);
                if (aNum != null && eNum != null) {
                    BigDecimal diff = aNum.subtract(eNum).abs();
                    return diff.compareTo(new BigDecimal("0.000001")) <= 0;
                }
                return valuesEqual(actual, expected);
            case "EXACT":
            default:
                return valuesEqual(actual, expected);
        }
    }

    private BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean readBooleanSetting(JsonNode node, boolean defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asInt() != 0;
        }
        if (node.isTextual()) {
            String value = node.asText("").trim().toLowerCase();
            if ("true".equals(value) || "1".equals(value) || "yes".equals(value) || "y".equals(value)
                    || "on".equals(value)) {
                return true;
            }
            if ("false".equals(value) || "0".equals(value) || "no".equals(value) || "n".equals(value)
                    || "off".equals(value)) {
                return false;
            }
        }
        return defaultValue;
    }

    private double readDoubleSetting(JsonNode node, double defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().trim());
            } catch (Exception ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private JsonNode resolveInsertPayload(JsonNode rubric) {
        if (rubric == null || rubric.isMissingNode() || rubric.isNull()) {
            return objectMapper.createObjectNode();
        }

        JsonNode payload = rubric.path("grading_payload");
        if (payload != null && payload.isObject()) {
            return payload;
        }

        return rubric;
    }

    private JsonNode resolveInsertDatasets(JsonNode rubric, JsonNode payload) {
        JsonNode[] candidates = new JsonNode[] {
                payload.path("tables"),
                payload.path("expected_datasets"),
                rubric.path("tables"),
                rubric.path("expected_datasets")
        };

        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isArray() && candidate.size() > 0) {
                return candidate;
            }
        }

        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }

        return objectMapper.createArrayNode();
    }

    private JsonNode resolveInsertGradingRules(JsonNode rubric, JsonNode payload) {
        JsonNode[] candidates = new JsonNode[] {
                payload.path("grading_rules"),
                rubric.path("grading_rules")
        };

        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }

        return objectMapper.createArrayNode();
    }

    private boolean hasLegacyInsertExtraRowSettings(JsonNode settings) {
        if (settings == null || settings.isMissingNode() || settings.isNull()) {
            return false;
        }
        return settings.path("allow_extra_rows").isValueNode()
                || settings.path("penalty_per_extra_row").isValueNode();
    }

    private boolean hasInsertFailAllRule(JsonNode gradingRules) {
        if (!gradingRules.isArray()) {
            return false;
        }

        for (JsonNode ruleNode : gradingRules) {
            if (!ruleNode.isObject()) {
                continue;
            }
            if ("FAIL_ALL".equalsIgnoreCase(ruleNode.path("action").asText(""))) {
                return true;
            }
        }

        return false;
    }

    private Double resolveInsertRulePenaltyPoints(
            JsonNode gradingRules,
            String target,
            String condition,
            double tablePoints) {
        JsonNode ruleNode = findInsertRule(gradingRules, target, condition);
        if (ruleNode == null) {
            return null;
        }

        String action = ruleNode.path("action").asText("").trim();
        if (action.isBlank()) {
            return null;
        }

        double penaltyValue = Math.max(0d, readDoubleSetting(ruleNode.path("penalty_value"), 0d));
        if ("DEDUCT_POINTS".equalsIgnoreCase(action)) {
            return penaltyValue;
        }

        if ("DEDUCT_PERCENTAGE".equalsIgnoreCase(action)) {
            return Math.max(0d, tablePoints * penaltyValue / 100d);
        }

        return null;
    }

    private boolean hasSelectRubricTestCases(ExamQuestion question) {
        if (question == null || question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return false;
        }

        try {
            JsonNode rubric = objectMapper.readTree(question.getGradingRubric());
            JsonNode testCases = rubric.path("grading_payload").path("test_cases");
            return testCases.isArray() && testCases.size() > 0;
        } catch (Exception e) {
            log.warn("Không thể phân tích test_cases SELECT cho câu {}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    private GradeDecision gradeSelectByRubricTestCases(
            Exam exam,
            ExamSpecification specification,
            List<ExamQuestion> sortedQuestions,
            String baseSchemaName,
            ExamQuestion question,
            String studentQuery) {
        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;

        try {
            JsonNode rubric = objectMapper.readTree(question.getGradingRubric());
            JsonNode payload = rubric.path("grading_payload");
            JsonNode testCases = payload.path("test_cases");
            if (!testCases.isArray() || testCases.size() == 0) {
                return gradeSelectAcrossDatasets(specification, baseSchemaName, question, studentQuery);
            }

            JsonNode selectRules = resolveSelectGradingRules(question);
            boolean strictOrdering = readBooleanSetting(
                    payload.path("global_grading_rules").path("strict_ordering"),
                    false);

            BigDecimal totalDeduction = BigDecimal.ZERO;
            BigDecimal structuralDeduction = BigDecimal.ZERO;
            boolean structuralChecked = false;
            boolean allPassed = true;
            StringBuilder issues = new StringBuilder();

            for (int i = 0; i < testCases.size(); i++) {
                JsonNode tc = testCases.get(i);
                String caseId = tc.path("case_id").asText("TC_" + (i + 1));
                String caseName = tc.path("case_name").asText(caseId);
                BigDecimal caseMaxPenalty = BigDecimal
                        .valueOf(Math.max(0d, tc.path("penalty_value").asDouble(1.0)))
                        .setScale(4, RoundingMode.HALF_UP);
                String casePhase = "setup";
                String caseSchema = baseSchemaName + "_sel_" + question.getId() + "_" + i + "_"
                        + (System.currentTimeMillis() % 100000);

                try {
                    examSchemaService.resetSchema(caseSchema, false);

                    bootstrapSelectGradingSchema(
                            exam,
                            specification,
                            sortedQuestions,
                            caseSchema,
                            caseId,
                            issues);

                    runSelectSetupDependency(
                            sortedQuestions,
                            tc.path("setup_dependency_id").asText("").trim(),
                            caseSchema,
                            caseId,
                            issues);

                    String setupCustomScript = tc.path("setup_custom_script").asText("");
                    if (!setupCustomScript.isBlank()) {
                        if (containsForbiddenSchemaDdl(setupCustomScript)) {
                            throw new IllegalArgumentException(
                                    "setup_custom_script không được chứa CREATE/DROP TABLE hoặc ALTER TABLE chưa được hỗ trợ.");
                        }
                        clearAllDataInSchema(caseSchema);
                        executeSetupScriptWithFkFallback(caseSchema, setupCustomScript, caseId, issues);
                    }

                    casePhase = "student_query";
                    List<Map<String, Object>> actualRows = examSchemaService.executeSql(caseSchema, studentQuery)
                            .getResultSet();
                    if (actualRows == null) {
                        actualRows = List.of();
                    }

                    casePhase = "teacher_query";
                    SelectExpectedRows expected = resolveSelectExpectedRows(
                            caseSchema,
                            question.getCorrectQuery(),
                            tc);

                    if (!structuralChecked && !actualRows.isEmpty() && !expected.columns().isEmpty()) {
                        structuralChecked = true;
                        structuralDeduction = calculateSelectStructuralDeductionForTestCases(
                                expected.columns(),
                                actualRows,
                                selectRules,
                                totalPoints,
                                issues);
                    }

                    casePhase = "grading";
                    int issuesBefore = issues.length();
                    BigDecimal caseDeduction = calculateSelectCaseDeductionForTestCase(
                            caseId,
                            caseName,
                            caseMaxPenalty,
                            expected.columns(),
                            expected.rows(),
                            actualRows,
                            strictOrdering,
                            selectRules,
                            issues);

                    if (caseDeduction.compareTo(BigDecimal.ZERO) > 0) {
                        allPassed = false;
                        totalDeduction = totalDeduction.add(caseDeduction);
                    }
                    if (GradingTraceCollector.isActive()) {
                        boolean casePassed = caseDeduction.compareTo(BigDecimal.ZERO) <= 0;
                        String traceMessage;
                        if (casePassed) {
                            traceMessage = "Test case đạt";
                        } else {
                            String addedIssues = issues.substring(issuesBefore).trim();
                            traceMessage = addedIssues.isEmpty() ? "Kết quả không khớp với đáp án mẫu" : addedIssues;
                        }
                        GradingTraceCollector.add(new GradingTraceItem(
                                GradingTraceItem.KIND_TEST_CASE,
                                casePassed ? GradingTraceItem.STATUS_PASS : GradingTraceItem.STATUS_FAIL,
                                caseName,
                                traceMessage,
                                caseId, caseName,
                                null, null, casePassed ? null : "DEDUCT_POINTS",
                                casePassed ? null : caseMaxPenalty,
                                null, caseMaxPenalty,
                                casePassed ? null : caseDeduction,
                                null, null,
                                "SELECT rubric test case"));
                    }
                } catch (Exception caseEx) {
                    String message = caseEx.getMessage() != null ? caseEx.getMessage() : caseEx.getClass().getSimpleName();
                    if ("setup".equals(casePhase)) {
                        appendSelectIssue(issues,
                                "[" + caseId + "] Setup thất bại, bỏ qua không trừ điểm: " + message);
                        if (GradingTraceCollector.isActive()) {
                            GradingTraceCollector.add(new GradingTraceItem(
                                    GradingTraceItem.KIND_TEACHER_CONFIG, GradingTraceItem.STATUS_WARN,
                                    caseId + " (setup)",
                                    message,
                                    caseId, caseName,
                                    null, null, null, null,
                                    null, caseMaxPenalty, null,
                                    null, null,
                                    "SELECT rubric test case — setup error"));
                        }
                        continue;
                    }

                    allPassed = false;
                    totalDeduction = totalDeduction.add(caseMaxPenalty);
                    appendSelectIssue(issues,
                            "[" + caseId + "] Giai đoạn " + casePhase + " thất bại, trừ "
                                    + caseMaxPenalty.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                    + " điểm: " + message);
                    if (GradingTraceCollector.isActive()) {
                        GradingTraceCollector.add(new GradingTraceItem(
                                GradingTraceItem.KIND_TEST_CASE, GradingTraceItem.STATUS_FAIL,
                                caseId + " (" + casePhase + ")",
                                message,
                                caseId, caseName,
                                null, null, null, null,
                                null, caseMaxPenalty, caseMaxPenalty,
                                null, null,
                                "SELECT rubric test case — " + casePhase + " error"));
                    }
                } finally {
                    try {
                        examSchemaService.dropSchema(caseSchema);
                    } catch (Exception e) {
                        log.warn("Không thể xóa schema test case SELECT [{}]: {}", caseSchema, e.getMessage());
                    }
                }
            }

            if (structuralDeduction.compareTo(BigDecimal.ZERO) > 0) {
                allPassed = false;
                totalDeduction = totalDeduction.add(structuralDeduction);
            }

            BigDecimal finalEarned = totalPoints.subtract(totalDeduction).setScale(2, RoundingMode.HALF_UP);
            if (finalEarned.compareTo(BigDecimal.ZERO) < 0) {
                finalEarned = BigDecimal.ZERO;
            }
            if (finalEarned.compareTo(totalPoints) > 0) {
                finalEarned = totalPoints;
            }

            if (allPassed && totalDeduction.compareTo(BigDecimal.ZERO) <= 0) {
                return GradeDecision.pass(finalEarned);
            }

            String errorMessage = issues.length() > 0
                    ? issues.toString().trim()
                    : "Kết quả SELECT không khớp với test case trong rubric.";
            return GradeDecision.partial(finalEarned, errorMessage);
        } catch (Exception e) {
            return GradeDecision.fail("Không thể chấm test case SELECT: " + e.getMessage());
        }
    }

    private int bootstrapSelectGradingSchema(
            Exam exam,
            ExamSpecification specification,
            List<ExamQuestion> sortedQuestions,
            String schemaName,
            String caseId,
            StringBuilder issues) {
        boolean isLoadDdl = exam != null
                && exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl());
        if (isLoadDdl) {
            if (specification == null || specification.getDdlScript() == null
                    || specification.getDdlScript().isBlank()) {
                throw new IllegalArgumentException("Đề thi đã bật nạp DDL nhưng đặc tả đang thiếu DDL script.");
            }
            examSchemaService.loadTemplateIntoSchema(schemaName, specification.getDdlScript(), null);
            return 0;
        }

        int preparedCount = 0;
        for (ExamQuestion q : sortedQuestions) {
            if (q.getQuestionType() != QuestionType.CREATE_TABLE) {
                continue;
            }
            if (q.getCorrectQuery() == null || q.getCorrectQuery().isBlank()) {
                continue;
            }

            try {
                executeSqlScriptBatches(schemaName, q.getCorrectQuery());
                preparedCount++;
            } catch (Exception ex) {
                String error = ex.getMessage() != null ? ex.getMessage() : "";
                boolean duplicateObject = error.contains("There is already an object named")
                        || error.contains("error code [2714]");
                if (!duplicateObject) {
                    appendSelectIssue(issues,
                            "[" + caseId + "] Không thể chạy đáp án CREATE_TABLE #" + q.getId()
                                    + " khi chuẩn bị schema SELECT: " + error);
                }
            }
        }
        return preparedCount;
    }

    private void runSelectSetupDependency(
            List<ExamQuestion> sortedQuestions,
            String setupDependencyId,
            String schemaName,
            String caseId,
            StringBuilder issues) {
        if (setupDependencyId == null || setupDependencyId.isBlank()) {
            return;
        }
        if (!setupDependencyId.matches("\\d+")) {
            appendSelectIssue(issues, "[" + caseId + "] Bỏ qua setup_dependency_id không phải số: " + setupDependencyId);
            return;
        }

        try {
            Long depId = Long.valueOf(setupDependencyId);
            ExamQuestion depQuestion = sortedQuestions.stream()
                    .filter(q -> q.getId() != null && q.getId().equals(depId))
                    .findFirst()
                    .orElse(null);
            if (depQuestion == null || depQuestion.getCorrectQuery() == null
                    || depQuestion.getCorrectQuery().isBlank()) {
                return;
            }
            executeSqlScriptBatches(schemaName, depQuestion.getCorrectQuery());
        } catch (Exception e) {
            appendSelectIssue(issues,
                    "[" + caseId + "] Không thể chạy setup_dependency_id " + setupDependencyId + ": "
                            + e.getMessage());
        }
    }

    private SelectExpectedRows resolveSelectExpectedRows(
            String schemaName,
            String correctQuery,
            JsonNode testCase) {
        if (correctQuery != null && !correctQuery.isBlank()) {
            List<Map<String, Object>> teacherRows = examSchemaService.executeSql(schemaName, correctQuery).getResultSet();
            if (teacherRows == null || teacherRows.isEmpty()) {
                return new SelectExpectedRows(List.of(), teacherRows == null ? List.of() : teacherRows);
            }
            return new SelectExpectedRows(new ArrayList<>(teacherRows.get(0).keySet()), teacherRows);
        }

        JsonNode expectedResult = testCase.path("expected_result");
        JsonNode columnsConfig = expectedResult.path("columns_config");
        JsonNode rowsNode = expectedResult.path("rows");

        List<String> columns = new ArrayList<>();
        if (columnsConfig.isArray()) {
            for (JsonNode columnNode : columnsConfig) {
                columns.add(columnNode.path("column_name").asText(""));
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        if (rowsNode.isArray()) {
            for (JsonNode rowNode : rowsNode) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    JsonNode valueNode = i < rowNode.size() ? rowNode.get(i) : null;
                    row.put(columns.get(i), valueNode == null || valueNode.isNull() ? null : valueNode.asText());
                }
                rows.add(row);
            }
        }

        return new SelectExpectedRows(columns, rows);
    }

    private BigDecimal calculateSelectStructuralDeductionForTestCases(
            List<String> expectedColumns,
            List<Map<String, Object>> actualRows,
            JsonNode selectRules,
            BigDecimal maxTotalPoints,
            StringBuilder issues) {
        List<String> actualColumns = actualRows == null || actualRows.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(actualRows.get(0).keySet());

        List<String> effectiveExpectedColumns = expectedColumns == null ? new ArrayList<>()
                : expectedColumns.stream()
                        .filter(col -> col != null && !col.isBlank())
                        .collect(Collectors.toCollection(ArrayList::new));

        if (effectiveExpectedColumns.isEmpty() || actualColumns.isEmpty()) {
            return BigDecimal.ZERO;
        }

        int nameMismatchAtSamePosition = 0;
        int minCols = Math.min(effectiveExpectedColumns.size(), actualColumns.size());
        for (int i = 0; i < minCols; i++) {
            if (!effectiveExpectedColumns.get(i).equalsIgnoreCase(actualColumns.get(i))) {
                nameMismatchAtSamePosition++;
            }
        }
        int trulyMissingColumns = Math.max(0, effectiveExpectedColumns.size() - actualColumns.size());
        int trulyExtraColumns = Math.max(0, actualColumns.size() - effectiveExpectedColumns.size());
        int missingColumns = nameMismatchAtSamePosition + trulyMissingColumns;
        int extraColumns = trulyExtraColumns;

        int columnOrderViolations = 0;
        if (nameMismatchAtSamePosition == 0
                && trulyMissingColumns == 0
                && trulyExtraColumns == 0
                && !sameColumnOrderIgnoreCase(effectiveExpectedColumns, actualColumns)) {
            columnOrderViolations = 1;
        }

        if (nameMismatchAtSamePosition == 0 && missingColumns == 0 && extraColumns == 0
                && columnOrderViolations == 0) {
            return BigDecimal.ZERO;
        }

        List<SelectRuleApplication> applications = List.of(
                applySelectRule(selectRules, "COLUMN", "NOT_EQUAL", nameMismatchAtSamePosition,
                        maxTotalPoints, 0.0, "sai tên cột ở " + nameMismatchAtSamePosition + " vị trí"),
                applySelectRule(selectRules, "COLUMN", "IS_MISSING", trulyMissingColumns,
                        maxTotalPoints, 0.0, "thiếu " + trulyMissingColumns + " cột"),
                applySelectRule(selectRules, "COLUMN", "IS_EXTRA", extraColumns,
                        maxTotalPoints, 0.0, "dư " + extraColumns + " cột"),
                applySelectRule(selectRules, "COLUMN_ORDER", "OUT_OF_ORDER", columnOrderViolations,
                        maxTotalPoints, 0.0, "sai thứ tự cột"));

        BigDecimal totalDeduction = BigDecimal.ZERO;
        boolean failAll = false;
        for (SelectRuleApplication application : applications) {
            if (!application.violationPresent()) {
                continue;
            }
            addSelectRuleTrace(
                    null,
                    "Cấu trúc cột SELECT",
                    application,
                    maxTotalPoints,
                    "SELECT structural rubric");
            if (application.failAllTriggered()) {
                failAll = true;
            }
            if (application.deduction().compareTo(BigDecimal.ZERO) > 0) {
                totalDeduction = totalDeduction.add(application.deduction());
            }
            if (application.message() != null && !application.message().isBlank()) {
                appendSelectIssue(issues, "[Cấu trúc cột] " + application.message());
            }
        }

        if (failAll) {
            return maxTotalPoints.setScale(2, RoundingMode.HALF_UP);
        }
        if (totalDeduction.compareTo(maxTotalPoints) > 0) {
            totalDeduction = maxTotalPoints;
        }
        return totalDeduction.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSelectCaseDeductionForTestCase(
            String caseId,
            String caseName,
            BigDecimal caseMaxPenalty,
            List<String> expectedColumns,
            List<Map<String, Object>> expectedRows,
            List<Map<String, Object>> actualRows,
            boolean strictOrdering,
            JsonNode selectRules,
            StringBuilder issues) {
        List<Map<String, Object>> safeExpectedRows = expectedRows == null ? List.of() : expectedRows;
        List<Map<String, Object>> safeActualRows = actualRows == null ? List.of() : actualRows;

        List<String> actualColumns = safeActualRows.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(safeActualRows.get(0).keySet());

        List<String> effectiveExpectedColumns = expectedColumns == null ? new ArrayList<>()
                : expectedColumns.stream()
                        .filter(column -> column != null && !column.isBlank())
                        .collect(Collectors.toCollection(ArrayList::new));
        if (effectiveExpectedColumns.isEmpty() && !actualColumns.isEmpty()) {
            effectiveExpectedColumns = new ArrayList<>(actualColumns);
        }

        List<String> comparisonColumns = !effectiveExpectedColumns.isEmpty()
                ? new ArrayList<>(effectiveExpectedColumns)
                : new ArrayList<>(actualColumns);
        if (comparisonColumns.isEmpty() && !safeExpectedRows.isEmpty()) {
            comparisonColumns.addAll(safeExpectedRows.get(0).keySet());
        }

        List<Map<String, Object>> remappedActualRows = remapActualRowsByPosition(
                safeActualRows,
                actualColumns,
                effectiveExpectedColumns);

        if (compareSelectResultStrict(remappedActualRows, safeExpectedRows, strictOrdering, comparisonColumns)) {
            return BigDecimal.ZERO;
        }

        int expectedRowsCount = safeExpectedRows.size();
        int actualRowsCount = safeActualRows.size();
        int missingRows = Math.max(0, expectedRowsCount - actualRowsCount);
        int extraRows = Math.max(0, actualRowsCount - expectedRowsCount);

        JsonNode rowOrderRule = findInsertRule(selectRules, "ROW_ORDER", "OUT_OF_ORDER");
        int rowOrderViolations = 0;
        if (strictOrdering && rowOrderRule != null && !hasInsertModifier(rowOrderRule, "SORT_ASC")) {
            rowOrderViolations = countSelectRowOrderViolations(remappedActualRows, safeExpectedRows, comparisonColumns);
            if (rowOrderViolations == 0) {
                rowOrderViolations = 1;
            }
        }

        JsonNode cellNotEqualRule = findInsertRule(selectRules, "CELL_VALUE", "NOT_EQUAL");
        JsonNode cellNullRule = findInsertRule(selectRules, "CELL_VALUE", "IS_NULL");
        JsonNode cellCompareModifiers = firstNonEmptyModifiers(
                extractInsertRuleModifiers(cellNotEqualRule),
                extractInsertRuleModifiers(cellNullRule));

        List<SelectRowPair> rowPairs = buildSelectRowPairs(
                remappedActualRows,
                safeExpectedRows,
                comparisonColumns,
                strictOrdering,
                cellCompareModifiers);
        int wrongCells = countSelectCellMismatches(rowPairs, comparisonColumns, cellCompareModifiers);
        int nullViolations = countSelectNullViolations(rowPairs, comparisonColumns, cellCompareModifiers);

        List<SelectRuleApplication> applications = List.of(
                applySelectRule(selectRules, "ROW", "IS_MISSING", missingRows,
                        caseMaxPenalty, 0.0, "thiếu " + missingRows + " dòng"),
                applySelectRule(selectRules, "ROW", "IS_EXTRA", extraRows,
                        caseMaxPenalty, 0.0, "dư " + extraRows + " dòng"),
                applySelectRule(selectRules, "CELL_VALUE", "NOT_EQUAL", wrongCells,
                        caseMaxPenalty, 0.0, "sai " + wrongCells + " ô dữ liệu"),
                applySelectRule(selectRules, "CELL_VALUE", "IS_NULL", nullViolations,
                        caseMaxPenalty, 0.0, "null " + nullViolations + " cells"),
                applySelectRule(selectRules, "ROW_ORDER", "OUT_OF_ORDER", rowOrderViolations,
                        caseMaxPenalty, 0.0, "sai thứ tự dòng"));

        BigDecimal totalCaseDeduction = BigDecimal.ZERO;
        int matchedRuleCount = 0;
        boolean failAllTriggered = false;
        StringBuilder caseIssues = new StringBuilder();
        for (SelectRuleApplication application : applications) {
            if (!application.violationPresent()) {
                continue;
            }
            addSelectRuleTrace(
                    caseId,
                    caseName,
                    application,
                    caseMaxPenalty,
                    "SELECT test case rubric");
            if (application.ruleMatched()) {
                matchedRuleCount++;
            }
            if (application.failAllTriggered()) {
                failAllTriggered = true;
            }
            if (application.deduction().compareTo(BigDecimal.ZERO) > 0) {
                totalCaseDeduction = totalCaseDeduction.add(application.deduction());
            }
            if (application.message() != null && !application.message().isBlank()) {
                appendSelectIssue(caseIssues, application.message());
            }
        }

        if (failAllTriggered) {
            totalCaseDeduction = caseMaxPenalty;
        } else if (matchedRuleCount == 0) {
            appendSelectIssue(issues,
                    "[" + caseId + "] " + caseName
                            + ": phát hiện sai khác nhưng không có rule SELECT tương ứng; không trừ điểm.");
            return BigDecimal.ZERO;
        }

        if (totalCaseDeduction.compareTo(caseMaxPenalty) > 0) {
            totalCaseDeduction = caseMaxPenalty;
        }

        BigDecimal rounded = totalCaseDeduction.setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.ZERO) > 0) {
            String detail = caseIssues.length() > 0 ? caseIssues.toString().trim() : "kết quả không khớp";
            appendSelectIssue(issues,
                    "[" + caseId + "] " + caseName + ": " + detail
                            + " -> trừ " + rounded.toPlainString() + " điểm.");
        }
        return rounded;
    }

    private boolean compareSelectResultStrict(
            List<Map<String, Object>> actualRows,
            List<Map<String, Object>> expectedRows,
            boolean strictOrdering,
            List<String> comparisonColumns) {
        if (actualRows == null || expectedRows == null || actualRows.size() != expectedRows.size()) {
            return false;
        }

        List<String> actualSignatures = new ArrayList<>();
        for (Map<String, Object> actualRow : actualRows) {
            actualSignatures.add(buildSelectRowSignature(actualRow, comparisonColumns));
        }

        List<String> expectedSignatures = new ArrayList<>();
        for (Map<String, Object> expectedRow : expectedRows) {
            expectedSignatures.add(buildSelectRowSignature(expectedRow, comparisonColumns));
        }

        if (!strictOrdering) {
            Collections.sort(actualSignatures);
            Collections.sort(expectedSignatures);
        }
        return actualSignatures.equals(expectedSignatures);
    }

    private boolean containsForbiddenSchemaDdl(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }

        String normalized = sql.toUpperCase(Locale.ROOT);
        if (normalized.contains("CREATE TABLE") || normalized.contains("DROP TABLE")) {
            return true;
        }
        if (!normalized.contains("ALTER TABLE")) {
            return false;
        }

        String[] statements = normalized.split(";");
        for (String raw : statements) {
            String stmt = raw.trim();
            if (stmt.isBlank() || !stmt.contains("ALTER TABLE")) {
                continue;
            }
            boolean allowNocheck = stmt.matches("(?s).*ALTER\\s+TABLE.*NOCHECK\\s+CONSTRAINT.*");
            boolean allowCheck = stmt.matches("(?s).*ALTER\\s+TABLE.*CHECK\\s+CONSTRAINT.*");
            if (!allowNocheck && !allowCheck) {
                return true;
            }
        }
        return false;
    }

    private void executeSetupScriptWithFkFallback(
            String schemaName,
            String setupScript,
            String caseId,
            StringBuilder issues) {
        try {
            executeSqlScriptBatches(schemaName, setupScript);
        } catch (Exception ex) {
            String message = ex.getMessage() != null ? ex.getMessage() : "";
            boolean isFkConflict = message.contains("FOREIGN KEY constraint")
                    || message.toLowerCase(Locale.ROOT).contains("foreign key");
            if (!isFkConflict) {
                throw ex;
            }

            appendSelectIssue(issues,
                    "[" + caseId + "] setup_custom_script gặp xung đột FK; thử lại với NOCHECK CONSTRAINT.");
            clearAllDataInSchema(schemaName);
            setAllConstraintsEnabled(schemaName, false);
            try {
                try {
                    executeSqlScriptBatches(schemaName, setupScript);
                } catch (Exception retryEx) {
                    String relaxedScript = stripRecheckConstraintStatements(setupScript);
                    if (relaxedScript.isBlank() || relaxedScript.equals(setupScript)) {
                        throw retryEx;
                    }
                    clearAllDataInSchema(schemaName);
                    setAllConstraintsEnabled(schemaName, false);
                    executeSqlScriptBatches(schemaName, relaxedScript);
                }
            } finally {
                try {
                    setAllConstraintsEnabled(schemaName, true);
                } catch (Exception recheckEx) {
                    appendSelectIssue(issues,
                            "[" + caseId + "] Ràng buộc FK vẫn không hợp lệ sau setup; tiếp tục chạy với NOCHECK.");
                    try {
                        setAllConstraintsEnabled(schemaName, false);
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    private String stripRecheckConstraintStatements(String setupScript) {
        if (setupScript == null || setupScript.isBlank()) {
            return "";
        }

        StringBuilder filtered = new StringBuilder();
        String[] statements = setupScript.split(";");
        for (String rawStatement : statements) {
            String statement = rawStatement == null ? "" : rawStatement.trim();
            if (statement.isBlank()) {
                continue;
            }
            String normalized = statement.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
            boolean isRecheckConstraint = normalized.matches("ALTER TABLE .* WITH CHECK CHECK CONSTRAINT ALL")
                    || normalized.matches("ALTER TABLE .* CHECK CONSTRAINT ALL")
                    || normalized.matches("ALTER TABLE .* WITH CHECK CHECK CONSTRAINT \\[?[^\\]]+\\]?")
                    || normalized.matches("ALTER TABLE .* CHECK CONSTRAINT \\[?[^\\]]+\\]?");
            if (isRecheckConstraint) {
                continue;
            }
            filtered.append(statement).append(";\n");
        }
        return filtered.toString().trim();
    }

    private void clearAllDataInSchema(String schemaName) {
        String safeSchema = schemaName.replaceAll("[^a-zA-Z0-9_]", "");
        setAllConstraintsEnabled(safeSchema, false);
        try {
            List<Map<String, Object>> tables = examSchemaService.executeAdminSql(
                    "SELECT t.name AS TABLE_NAME "
                            + "FROM sys.tables t "
                            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
                            + "WHERE s.name = '" + safeSchema + "'")
                    .getResultSet();

            for (Map<String, Object> row : tables) {
                Object tableNameObj = row.get("TABLE_NAME");
                if (tableNameObj == null) {
                    continue;
                }
                String tableName = String.valueOf(tableNameObj).replaceAll("[^a-zA-Z0-9_]", "");
                examSchemaService.executeAdminSql("DELETE FROM [" + safeSchema + "].[" + tableName + "]");
            }
        } finally {
            setAllConstraintsEnabled(safeSchema, true);
        }
    }

    private JsonNode findInsertRule(JsonNode gradingRules, String target, String condition) {
        if (!gradingRules.isArray()) {
            return null;
        }

        for (JsonNode ruleNode : gradingRules) {
            if (!ruleNode.isObject()) {
                continue;
            }

            String ruleTarget = ruleNode.path("target").asText("").trim();
            String ruleCondition = ruleNode.path("condition").asText("").trim();
            if (target.equalsIgnoreCase(ruleTarget) && condition.equalsIgnoreCase(ruleCondition)) {
                return ruleNode;
            }
        }

        return null;
    }

    private GradeDecision gradeSelectAcrossDatasets(
            ExamSpecification specification,
            String schemaName,
            ExamQuestion question,
            String studentQuery) {
        if (question.getCorrectQuery() == null || question.getCorrectQuery().isBlank()) {
            return GradeDecision.fail("Thiếu correctQuery cho câu SELECT.");
        }
        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;

        // Fallback: no specification → grade directly on current schema state
        if (specification == null
                || specification.getDdlScript() == null
                || specification.getDdlScript().isBlank()) {
            return gradeSelectDirectOnCurrentSchema(schemaName, question, studentQuery, totalPoints);
        }

        List<SpecDataset> activeDatasets = specification.getDatasets() == null ? List.of()
                : specification.getDatasets().stream()
                        .filter(SpecDataset::isActive)
                        .sorted(Comparator.comparingInt(SpecDataset::getOrderIndex))
                        .toList();

        JsonNode selectRules = resolveSelectGradingRules(question);
        boolean hasRuleBasedScoring = hasSelectGradingRules(selectRules);

        List<SelectDatasetSpec> datasetsToGrade = new ArrayList<>();
        if (activeDatasets.isEmpty()) {
            datasetsToGrade.add(new SelectDatasetSpec("fallback-no-dataset", null));
        } else {
            for (SpecDataset dataset : activeDatasets) {
                String datasetLabel = "dataset[" + dataset.getId() + ":" + dataset.getName() + "]";
                datasetsToGrade.add(new SelectDatasetSpec(datasetLabel, dataset.getDataScript()));
            }
        }

        if (!hasRuleBasedScoring) {
            for (SelectDatasetSpec datasetSpec : datasetsToGrade) {
                SelectDatasetDecision decision = gradeSelectWithSingleDatasetStrict(
                        schemaName,
                        specification.getDdlScript(),
                        datasetSpec.datasetScript(),
                        datasetSpec.datasetLabel(),
                        question,
                        studentQuery);
                if (!decision.allChecksPassed()) {
                    if (GradingTraceCollector.isActive()) {
                        GradingTraceCollector.add(new GradingTraceItem(
                                GradingTraceItem.KIND_SUMMARY,
                                GradingTraceItem.STATUS_FAIL,
                                "Kiểm tra SELECT (so sánh dataset)",
                                decision.errorMessage() != null ? decision.errorMessage() : "Kết quả không khớp",
                                null, null, null, null, null, null,
                                BigDecimal.ZERO, totalPoints,
                                totalPoints,
                                null, null,
                                "So sánh kết quả SELECT qua dataset(s)"));
                    }
                    return GradeDecision.fail(decision.errorMessage());
                }
            }
            if (GradingTraceCollector.isActive()) {
                GradingTraceCollector.add(new GradingTraceItem(
                        GradingTraceItem.KIND_SUMMARY,
                        GradingTraceItem.STATUS_PASS,
                        "Kiểm tra SELECT (so sánh dataset)",
                        "Kết quả SELECT khớp",
                        null, null, null, null, null, null,
                        totalPoints, totalPoints,
                        null,
                        null, null,
                        "So sánh kết quả SELECT qua dataset(s)"));
            }
            return GradeDecision.pass(totalPoints);
        }

        BigDecimal earnedTotal = BigDecimal.ZERO;
        BigDecimal distributedPoints = BigDecimal.ZERO;
        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassed = true;
        boolean failAllTriggered = false;

        BigDecimal baseDatasetPoints = totalPoints;
        if (!datasetsToGrade.isEmpty()) {
            baseDatasetPoints = totalPoints.divide(
                    BigDecimal.valueOf(datasetsToGrade.size()),
                    8,
                    RoundingMode.HALF_UP);
        }

        for (int i = 0; i < datasetsToGrade.size(); i++) {
            SelectDatasetSpec datasetSpec = datasetsToGrade.get(i);
            BigDecimal datasetMaxPoints = (i == datasetsToGrade.size() - 1)
                    ? totalPoints.subtract(distributedPoints)
                    : baseDatasetPoints;
            if (datasetMaxPoints.compareTo(BigDecimal.ZERO) < 0) {
                datasetMaxPoints = BigDecimal.ZERO;
            }
            distributedPoints = distributedPoints.add(datasetMaxPoints);

            SelectDatasetDecision decision = gradeSelectWithSingleDatasetByRules(
                    schemaName,
                    specification.getDdlScript(),
                    datasetSpec.datasetScript(),
                    datasetSpec.datasetLabel(),
                    question,
                    studentQuery,
                    selectRules,
                    datasetMaxPoints);

            if (decision.executionFailed()) {
                return GradeDecision.fail(decision.errorMessage());
            }

            earnedTotal = earnedTotal.add(decision.earnedPoints());
            if (!decision.allChecksPassed()) {
                allPassed = false;
            }
            if (decision.failAllTriggered()) {
                failAllTriggered = true;
            }
            if (decision.errorMessage() != null && !decision.errorMessage().isBlank()) {
                appendSelectIssue(errorBuilder, decision.errorMessage());
            }
        }

        if (failAllTriggered) {
            allPassed = false;
            earnedTotal = BigDecimal.ZERO;
            appendSelectIssue(errorBuilder,
                    "Rubric SELECT có rule FAIL_ALL: câu này bị 0 điểm toàn bộ.");
        }

        if (earnedTotal.compareTo(totalPoints) > 0) {
            earnedTotal = totalPoints;
        }
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0) {
            earnedTotal = BigDecimal.ZERO;
        }
        earnedTotal = earnedTotal.setScale(2, RoundingMode.HALF_UP);

        if (allPassed && earnedTotal.compareTo(totalPoints.setScale(2, RoundingMode.HALF_UP)) >= 0) {
            if (GradingTraceCollector.isActive()) {
                GradingTraceCollector.add(new GradingTraceItem(
                        GradingTraceItem.KIND_SUMMARY,
                        GradingTraceItem.STATUS_PASS,
                        "Kiểm tra SELECT (so sánh dataset)",
                        "Kết quả SELECT khớp",
                        null, null, null, null, null, null,
                        earnedTotal, totalPoints,
                        null,
                        null, null,
                        "So sánh kết quả SELECT qua dataset(s)"));
            }
            return GradeDecision.pass(earnedTotal);
        }

        String errorMessage = errorBuilder.length() > 0
                ? errorBuilder.toString().trim()
                : "Kết quả SELECT không khớp rubric chấm điểm.";
        if (GradingTraceCollector.isActive()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_SUMMARY,
                    GradingTraceItem.STATUS_FAIL,
                    "Kiểm tra SELECT (so sánh dataset)",
                    errorMessage,
                    null, null, null, null, null, null,
                    earnedTotal, totalPoints,
                    totalPoints.subtract(earnedTotal),
                    null, null,
                    "So sánh kết quả SELECT qua dataset(s)"));
        }
        return GradeDecision.partial(earnedTotal, errorMessage);
    }

    /**
     * Fallback grading for SELECT questions when no ExamSpecification is
     * attached to the exam. Runs both the student query and the correct query
     * on the schema as-is (no DDL reload, no multi-dataset loop) and compares
     * the results.
     */
    private GradeDecision gradeSelectDirectOnCurrentSchema(
            String schemaName,
            ExamQuestion question,
            String studentQuery,
            BigDecimal totalPoints) {
        try {
            List<Map<String, Object>> actual = examSchemaService.executeSql(schemaName, studentQuery).getResultSet();
            List<Map<String, Object>> expected = examSchemaService.executeSql(schemaName, question.getCorrectQuery())
                    .getResultSet();

            boolean requireStrictOrder = question.getCorrectQuery() != null
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            // --- rule-based grading (if rules exist) ---
            JsonNode selectRules = resolveSelectGradingRules(question);
            boolean hasRuleBasedScoring = hasSelectGradingRules(selectRules);

            if (!hasRuleBasedScoring) {
                // Simple strict comparison
                if (compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                    if (GradingTraceCollector.isActive()) {
                        GradingTraceCollector.add(new GradingTraceItem(
                                GradingTraceItem.KIND_SUMMARY,
                                GradingTraceItem.STATUS_PASS,
                                "Kiểm tra SELECT (so sánh trực tiếp)",
                                "Kết quả SELECT khớp hoàn toàn với đáp án.",
                                null, null, null, null, null, null,
                                totalPoints, totalPoints, null,
                                null, null,
                                "So sánh kết quả SELECT trên schema hiện tại"));
                    }
                    return GradeDecision.pass(totalPoints);
                }
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_SUMMARY,
                            GradingTraceItem.STATUS_FAIL,
                            "Kiểm tra SELECT (so sánh trực tiếp)",
                            "Kết quả SELECT không khớp với đáp án.",
                            null, null, null, null, null, null,
                            BigDecimal.ZERO, totalPoints, totalPoints,
                            null, null,
                            "So sánh kết quả SELECT trên schema hiện tại"));
                }
                return GradeDecision.fail("Kết quả SELECT không khớp với đáp án.");
            }

            // Exact match → full points immediately
            if (compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_SUMMARY,
                            GradingTraceItem.STATUS_PASS,
                            "Kiểm tra SELECT (so sánh trực tiếp)",
                            "Kết quả SELECT khớp hoàn toàn với đáp án (exact match).",
                            null, null, null, null, null, null,
                            totalPoints, totalPoints, null,
                            null, null,
                            "So sánh kết quả SELECT trên schema hiện tại"));
                }
                return GradeDecision.pass(totalPoints);
            }

            // Delegate to rule-based scoring with a single "virtual" dataset
            SelectDatasetDecision decision = gradeSelectWithSingleDatasetByRulesOnCurrentResults(
                    actual, expected, question, studentQuery, selectRules, totalPoints, requireStrictOrder);

            if (decision.executionFailed()) {
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_SUMMARY,
                            GradingTraceItem.STATUS_FAIL,
                            "Kiểm tra SELECT (so sánh trực tiếp)",
                            decision.errorMessage(),
                            null, null, null, null, null, null,
                            BigDecimal.ZERO, totalPoints, totalPoints,
                            null, null,
                            "So sánh kết quả SELECT trên schema hiện tại"));
                }
                return GradeDecision.fail(decision.errorMessage());
            }
            if (decision.failAllTriggered()) {
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_SUMMARY,
                            GradingTraceItem.STATUS_FAIL,
                            "Kiểm tra SELECT (so sánh trực tiếp)",
                            "Rubric SELECT có rule FAIL_ALL: câu này bị 0 điểm toàn bộ.",
                            null, null, null, null, null, null,
                            BigDecimal.ZERO, totalPoints, totalPoints,
                            null, null,
                            "So sánh kết quả SELECT trên schema hiện tại"));
                }
                return GradeDecision.fail(
                        "Rubric SELECT có rule FAIL_ALL: câu này bị 0 điểm toàn bộ.");
            }

            BigDecimal earned = decision.earnedPoints();
            if (earned.compareTo(totalPoints) > 0)
                earned = totalPoints;
            if (earned.compareTo(BigDecimal.ZERO) < 0)
                earned = BigDecimal.ZERO;
            earned = earned.setScale(2, RoundingMode.HALF_UP);

            if (decision.allChecksPassed()
                    && earned.compareTo(totalPoints.setScale(2, RoundingMode.HALF_UP)) >= 0) {
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_SUMMARY,
                            GradingTraceItem.STATUS_PASS,
                            "Kiểm tra SELECT (so sánh trực tiếp)",
                            "Tất cả kiểm tra rubric SELECT đạt.",
                            null, null, null, null, null, null,
                            earned, totalPoints, null,
                            null, null,
                            "So sánh kết quả SELECT trên schema hiện tại"));
                }
                return GradeDecision.pass(earned);
            }

            String errorMessage = decision.errorMessage() != null && !decision.errorMessage().isBlank()
                    ? decision.errorMessage()
                    : "Kết quả SELECT không khớp rubric chấm điểm.";
            if (GradingTraceCollector.isActive()) {
                GradingTraceCollector.add(new GradingTraceItem(
                        GradingTraceItem.KIND_SUMMARY,
                        GradingTraceItem.STATUS_FAIL,
                        "Kiểm tra SELECT (so sánh trực tiếp)",
                        errorMessage,
                        null, null, null, null, null, null,
                        earned, totalPoints, totalPoints.setScale(2, RoundingMode.HALF_UP).subtract(earned),
                        null, null,
                        "So sánh kết quả SELECT trên schema hiện tại"));
            }
            return GradeDecision.partial(earned, errorMessage);
        } catch (Exception e) {
            if (GradingTraceCollector.isActive()) {
                GradingTraceCollector.add(new GradingTraceItem(
                        GradingTraceItem.KIND_SUMMARY,
                        GradingTraceItem.STATUS_FAIL,
                        "Kiểm tra SELECT (so sánh trực tiếp)",
                        "Lỗi khi chấm SELECT: " + e.getMessage(),
                        null, null, null, null, null, null,
                        BigDecimal.ZERO, totalPoints, totalPoints,
                        null, null,
                        "So sánh kết quả SELECT trên schema hiện tại"));
            }
            return GradeDecision.fail("Lỗi khi chấm SELECT: " + e.getMessage());
        }
    }

    /**
     * Rule-based scoring on pre-computed result sets (no schema manipulation).
     */
    private SelectDatasetDecision gradeSelectWithSingleDatasetByRulesOnCurrentResults(
            List<Map<String, Object>> actual,
            List<Map<String, Object>> expected,
            ExamQuestion question,
            String studentQuery,
            JsonNode gradingRules,
            BigDecimal datasetMaxPoints,
            boolean requireStrictOrder) {
        try {
            List<String> expectedColumns = extractSelectColumns(expected);
            List<String> actualColumns = extractSelectColumns(actual);
            List<String> comparisonColumns = !expectedColumns.isEmpty() ? expectedColumns : actualColumns;

            List<Map<String, Object>> remappedActual = remapActualRowsByPosition(
                    actual, actualColumns, expectedColumns);

            int expectedRowsCount = expected == null ? 0 : expected.size();
            int actualRowsCount = actual == null ? 0 : actual.size();
            int missingRows = Math.max(0, expectedRowsCount - actualRowsCount);
            int extraRows = Math.max(0, actualRowsCount - expectedRowsCount);

            int nameMismatchAtSamePosition = 0;
            int minCols = Math.min(expectedColumns.size(), actualColumns.size());
            for (int i = 0; i < minCols; i++) {
                if (!expectedColumns.get(i).equalsIgnoreCase(actualColumns.get(i))) {
                    nameMismatchAtSamePosition++;
                }
            }
            int trulyMissingColumns = Math.max(0, expectedColumns.size() - actualColumns.size());
            int trulyExtraColumns = Math.max(0, actualColumns.size() - expectedColumns.size());
            int missingColumns = nameMismatchAtSamePosition + trulyMissingColumns;
            int extraColumns = trulyExtraColumns;

            int columnOrderViolations = 0;
            if (nameMismatchAtSamePosition == 0 && trulyMissingColumns == 0 && trulyExtraColumns == 0
                    && !expectedColumns.isEmpty() && !actualColumns.isEmpty()
                    && !sameColumnOrderIgnoreCase(expectedColumns, actualColumns)) {
                columnOrderViolations = 1;
            }

            JsonNode rowOrderRule = findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
            int rowOrderViolations = 0;
            if (requireStrictOrder && rowOrderRule != null && !hasInsertModifier(rowOrderRule, "SORT_ASC")) {
                rowOrderViolations = countSelectRowOrderViolations(remappedActual, expected, comparisonColumns);
                if (rowOrderViolations == 0) {
                    rowOrderViolations = 1;
                }
            }

            JsonNode cellNotEqualRule = findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
            JsonNode cellNullRule = findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
            JsonNode cellCompareModifiers = firstNonEmptyModifiers(
                    extractInsertRuleModifiers(cellNotEqualRule),
                    extractInsertRuleModifiers(cellNullRule));

            List<SelectRowPair> rowPairs = buildSelectRowPairs(
                    remappedActual, expected, comparisonColumns, requireStrictOrder, cellCompareModifiers);
            int wrongCells = countSelectCellMismatches(rowPairs, comparisonColumns, cellCompareModifiers);
            int nullViolations = countSelectNullViolations(rowPairs, comparisonColumns, cellCompareModifiers);

            double datasetPoints = datasetMaxPoints.doubleValue();
            int expectedColumnsCount = Math.max(1, comparisonColumns.size());
            int expectedRowsForPenalty = Math.max(1, expectedRowsCount);
            double rowPenaltyDefault = datasetPoints / expectedRowsForPenalty;
            double columnPenaltyDefault = datasetPoints / expectedColumnsCount;
            double cellPenaltyDefault = rowPenaltyDefault / expectedColumnsCount;

            List<SelectRuleApplication> applications = List.of(
                    applySelectRule(gradingRules, "ROW", "IS_MISSING", missingRows,
                            datasetMaxPoints, rowPenaltyDefault, "thiếu " + missingRows + " dòng"),
                    applySelectRule(gradingRules, "ROW", "IS_EXTRA", extraRows,
                            datasetMaxPoints, rowPenaltyDefault, "dư " + extraRows + " dòng"),
                    applySelectRule(gradingRules, "CELL_VALUE", "NOT_EQUAL", wrongCells,
                            datasetMaxPoints, cellPenaltyDefault, "sai " + wrongCells + " o du lieu"),
                    applySelectRule(gradingRules, "CELL_VALUE", "IS_NULL", nullViolations,
                            datasetMaxPoints, cellPenaltyDefault, "co " + nullViolations + " o gia tri rong"),
                    applySelectRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER", rowOrderViolations,
                            datasetMaxPoints, rowPenaltyDefault, "sai thứ tự " + rowOrderViolations + " dòng"),
                    applySelectRule(gradingRules, "COLUMN_ORDER", "OUT_OF_ORDER", columnOrderViolations,
                            datasetMaxPoints, columnPenaltyDefault, "sai thu tu cot ket qua"),
                    applySelectRule(gradingRules, "COLUMN", "IS_MISSING", missingColumns,
                            datasetMaxPoints, columnPenaltyDefault, "thiếu " + missingColumns + " cột"),
                    applySelectRule(gradingRules, "COLUMN", "IS_EXTRA", extraColumns,
                            datasetMaxPoints, columnPenaltyDefault, "du " + extraColumns + " cot"));

            double earned = datasetPoints;
            int matchedRuleCount = 0;
            boolean failAllTriggered = false;
            StringBuilder issueBuilder = new StringBuilder();

            for (SelectRuleApplication application : applications) {
                if (!application.violationPresent())
                    continue;
                addSelectRuleTrace(
                        null,
                        "Kết quả SELECT",
                        application,
                        datasetMaxPoints,
                        "SELECT result rubric");
                if (application.ruleMatched())
                    matchedRuleCount++;
                if (application.failAllTriggered())
                    failAllTriggered = true;
                if (application.deduction().compareTo(BigDecimal.ZERO) > 0) {
                    earned -= application.deduction().doubleValue();
                }
                if (application.message() != null && !application.message().isBlank()) {
                    appendSelectIssue(issueBuilder, application.message());
                }
            }

            if (failAllTriggered) {
                earned = 0d;
            } else if (matchedRuleCount == 0) {
                // Violations exist but no rules matched -> don't deduct points
                appendSelectIssue(issueBuilder,
                        "Phát hiện sai lệch nhưng không có quy tắc chấm phù hợp -> không trừ điểm.");
            }

            if (earned < 0d)
                earned = 0d;
            if (earned > datasetPoints)
                earned = datasetPoints;

            BigDecimal earnedPoints = BigDecimal.valueOf(earned).setScale(8, RoundingMode.HALF_UP);
            BigDecimal delta = datasetMaxPoints.subtract(earnedPoints).abs();
            boolean allChecksPassed = !failAllTriggered && delta.compareTo(new BigDecimal("0.0001")) <= 0;
            String message = issueBuilder.length() == 0
                    ? "Kết quả SELECT không khớp"
                    : issueBuilder.toString().trim();

            return SelectDatasetDecision.ruleResult(allChecksPassed, failAllTriggered,
                    allChecksPassed ? null : message, earnedPoints);
        } catch (Exception e) {
            return SelectDatasetDecision.executionFailure("Lỗi chấm SELECT: " + e.getMessage());
        }
    }

    private SelectDatasetDecision gradeSelectWithSingleDatasetStrict(
            String schemaName,
            String ddlScript,
            String datasetScript,
            String datasetLabel,
            ExamQuestion question,
            String studentQuery) {
        try {
            examSchemaService.resetSchema(schemaName, false);
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, datasetScript);

            List<Map<String, Object>> actual = examSchemaService.executeSql(schemaName, studentQuery).getResultSet();
            List<Map<String, Object>> expected = examSchemaService.executeSql(schemaName, question.getCorrectQuery())
                    .getResultSet();

            boolean requireStrictOrder = question.getCorrectQuery() != null
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            if (!compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                return SelectDatasetDecision.strictMismatch("Kết quả SELECT không khớp trên " + datasetLabel);
            }
            return SelectDatasetDecision.strictPass();
        } catch (Exception e) {
            return SelectDatasetDecision.executionFailure("Thất bại trên " + datasetLabel + ": " + e.getMessage());
        }
    }

    private SelectDatasetDecision gradeSelectWithSingleDatasetByRules(
            String schemaName,
            String ddlScript,
            String datasetScript,
            String datasetLabel,
            ExamQuestion question,
            String studentQuery,
            JsonNode gradingRules,
            BigDecimal datasetMaxPoints) {
        try {
            examSchemaService.resetSchema(schemaName, false);
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, datasetScript);

            List<Map<String, Object>> actual = examSchemaService.executeSql(schemaName, studentQuery).getResultSet();
            List<Map<String, Object>> expected = examSchemaService.executeSql(schemaName, question.getCorrectQuery())
                    .getResultSet();

            boolean requireStrictOrder = question.getCorrectQuery() != null
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            if (compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                return SelectDatasetDecision.ruleResult(true, false, null, datasetMaxPoints);
            }

            List<String> expectedColumns = extractSelectColumns(expected);
            List<String> actualColumns = extractSelectColumns(actual);
            List<String> comparisonColumns = !expectedColumns.isEmpty() ? expectedColumns : actualColumns;

            // Remap actual rows by column position so cell comparison works
            // even when student uses different column aliases (e.g., missing AS).
            List<Map<String, Object>> remappedActual = remapActualRowsByPosition(
                    actual, actualColumns, expectedColumns);

            int expectedRowsCount = expected == null ? 0 : expected.size();
            int actualRowsCount = actual == null ? 0 : actual.size();
            int missingRows = Math.max(0, expectedRowsCount - actualRowsCount);
            int extraRows = Math.max(0, actualRowsCount - expectedRowsCount);

            int nameMismatchAtSamePosition = 0;
            int minCols = Math.min(expectedColumns.size(), actualColumns.size());
            for (int i = 0; i < minCols; i++) {
                if (!expectedColumns.get(i).equalsIgnoreCase(actualColumns.get(i))) {
                    nameMismatchAtSamePosition++;
                }
            }
            int trulyMissingColumns = Math.max(0, expectedColumns.size() - actualColumns.size());
            int trulyExtraColumns = Math.max(0, actualColumns.size() - expectedColumns.size());

            int missingColumns = nameMismatchAtSamePosition + trulyMissingColumns;
            int extraColumns = trulyExtraColumns;

            int columnOrderViolations = 0;
            if (nameMismatchAtSamePosition == 0 && trulyMissingColumns == 0 && trulyExtraColumns == 0
                    && !expectedColumns.isEmpty() && !actualColumns.isEmpty()
                    && !sameColumnOrderIgnoreCase(expectedColumns, actualColumns)) {
                columnOrderViolations = 1;
            }

            JsonNode rowOrderRule = findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
            int rowOrderViolations = 0;
            if (requireStrictOrder && rowOrderRule != null && !hasInsertModifier(rowOrderRule, "SORT_ASC")) {
                rowOrderViolations = countSelectRowOrderViolations(remappedActual, expected, comparisonColumns);
                if (rowOrderViolations == 0) {
                    rowOrderViolations = 1;
                }
            }

            JsonNode cellNotEqualRule = findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
            JsonNode cellNullRule = findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
            JsonNode cellCompareModifiers = firstNonEmptyModifiers(
                    extractInsertRuleModifiers(cellNotEqualRule),
                    extractInsertRuleModifiers(cellNullRule));

            List<SelectRowPair> rowPairs = buildSelectRowPairs(
                    remappedActual,
                    expected,
                    comparisonColumns,
                    requireStrictOrder,
                    cellCompareModifiers);
            int wrongCells = countSelectCellMismatches(rowPairs, comparisonColumns, cellCompareModifiers);
            int nullViolations = countSelectNullViolations(rowPairs, comparisonColumns, cellCompareModifiers);

            double datasetPoints = datasetMaxPoints.doubleValue();
            int expectedColumnsCount = Math.max(1, comparisonColumns.size());
            int expectedRowsForPenalty = Math.max(1, expectedRowsCount);
            double rowPenaltyDefault = datasetPoints / expectedRowsForPenalty;
            double columnPenaltyDefault = datasetPoints / expectedColumnsCount;
            double cellPenaltyDefault = rowPenaltyDefault / expectedColumnsCount;

            List<SelectRuleApplication> applications = List.of(
                    applySelectRule(gradingRules, "ROW", "IS_MISSING", missingRows,
                            datasetMaxPoints, rowPenaltyDefault,
                            "thiếu " + missingRows + " dòng"),
                    applySelectRule(gradingRules, "ROW", "IS_EXTRA", extraRows,
                            datasetMaxPoints, rowPenaltyDefault,
                            "dư " + extraRows + " dòng"),
                    applySelectRule(gradingRules, "CELL_VALUE", "NOT_EQUAL", wrongCells,
                            datasetMaxPoints, cellPenaltyDefault,
                            "sai " + wrongCells + " o du lieu"),
                    applySelectRule(gradingRules, "CELL_VALUE", "IS_NULL", nullViolations,
                            datasetMaxPoints, cellPenaltyDefault,
                            "co " + nullViolations + " o gia tri rong"),
                    applySelectRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER", rowOrderViolations,
                            datasetMaxPoints, rowPenaltyDefault,
                            "sai thứ tự " + rowOrderViolations + " dòng"),
                    applySelectRule(gradingRules, "COLUMN_ORDER", "OUT_OF_ORDER", columnOrderViolations,
                            datasetMaxPoints, columnPenaltyDefault,
                            "sai thu tu cot ket qua"),
                    applySelectRule(gradingRules, "COLUMN", "IS_MISSING", missingColumns,
                            datasetMaxPoints, columnPenaltyDefault,
                            "thiếu " + missingColumns + " cột"),
                    applySelectRule(gradingRules, "COLUMN", "IS_EXTRA", extraColumns,
                            datasetMaxPoints, columnPenaltyDefault,
                            "du " + extraColumns + " cot"));

            double earned = datasetPoints;
            int matchedRuleCount = 0;
            boolean failAllTriggered = false;
            StringBuilder issueBuilder = new StringBuilder();

            for (SelectRuleApplication application : applications) {
                if (!application.violationPresent()) {
                    continue;
                }
                addSelectRuleTrace(
                        null,
                        datasetLabel,
                        application,
                        datasetMaxPoints,
                        "SELECT dataset rubric: " + datasetLabel);

                if (application.ruleMatched()) {
                    matchedRuleCount++;
                }

                if (application.failAllTriggered()) {
                    failAllTriggered = true;
                }

                if (application.deduction().compareTo(BigDecimal.ZERO) > 0) {
                    earned -= application.deduction().doubleValue();
                }

                if (application.message() != null && !application.message().isBlank()) {
                    appendSelectIssue(issueBuilder, application.message());
                }
            }

            if (failAllTriggered) {
                earned = 0d;
            } else if (matchedRuleCount == 0) {
                // Violations exist but no rules matched -> don't deduct points
                appendSelectIssue(issueBuilder,
                        "Phát hiện sai lệch nhưng không có quy tắc chấm phù hợp trên " + datasetLabel
                                + " -> không trừ điểm.");
            }

            if (earned < 0d) {
                earned = 0d;
            }
            if (earned > datasetPoints) {
                earned = datasetPoints;
            }

            BigDecimal earnedPoints = BigDecimal.valueOf(earned).setScale(8, RoundingMode.HALF_UP);
            BigDecimal delta = datasetMaxPoints.subtract(earnedPoints).abs();
            boolean allChecksPassed = !failAllTriggered && delta.compareTo(new BigDecimal("0.0001")) <= 0;
            String message = issueBuilder.length() == 0
                    ? "Kết quả SELECT không khớp trên " + datasetLabel
                    : "[" + datasetLabel + "] " + issueBuilder.toString().trim();

            return SelectDatasetDecision.ruleResult(
                    allChecksPassed,
                    failAllTriggered,
                    allChecksPassed ? null : message,
                    earnedPoints);
        } catch (Exception e) {
            return SelectDatasetDecision.executionFailure("Thất bại trên " + datasetLabel + ": " + e.getMessage());
        }
    }

    private JsonNode resolveSelectGradingRules(ExamQuestion question) {
        if (question == null || question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return objectMapper.createArrayNode();
        }

        try {
            JsonNode rubric = objectMapper.readTree(question.getGradingRubric());
            JsonNode payload = rubric.path("grading_payload");

            JsonNode[] candidates = new JsonNode[] {
                    payload.path("grading_rules"),
                    rubric.path("grading_rules")
            };

            for (JsonNode candidate : candidates) {
                if (candidate != null && candidate.isArray()) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            log.warn("Không thể phân tích grading_rules cho câu SELECT {}: {}", question.getId(), e.getMessage());
        }

        return objectMapper.createArrayNode();
    }

    private boolean hasSelectGradingRules(JsonNode gradingRules) {
        if (gradingRules == null || !gradingRules.isArray()) {
            return false;
        }

        for (JsonNode ruleNode : gradingRules) {
            if (!ruleNode.isObject()) {
                continue;
            }
            String target = ruleNode.path("target").asText("").trim();
            String condition = ruleNode.path("condition").asText("").trim();
            if (!target.isBlank() && !condition.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractSelectColumns(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(rows.get(0).keySet());
    }

    private List<Map<String, Object>> remapActualRowsByPosition(
            List<Map<String, Object>> actualRows,
            List<String> actualColumns,
            List<String> expectedColumns) {
        if (actualRows == null || actualRows.isEmpty()
                || expectedColumns == null || expectedColumns.isEmpty()) {
            return actualRows != null ? actualRows : List.of();
        }

        boolean allMatch = actualColumns.size() >= expectedColumns.size();
        if (allMatch) {
            for (int i = 0; i < expectedColumns.size(); i++) {
                if (i >= actualColumns.size()
                        || !expectedColumns.get(i).equalsIgnoreCase(actualColumns.get(i))) {
                    allMatch = false;
                    break;
                }
            }
        }
        if (allMatch) {
            return actualRows;
        }

        List<Map<String, Object>> remapped = new ArrayList<>();
        for (Map<String, Object> actualRow : actualRows) {
            Map<String, Object> newRow = new LinkedHashMap<>();
            for (int i = 0; i < expectedColumns.size(); i++) {
                String expectedCol = expectedColumns.get(i);
                Object value = null;
                if (i < actualColumns.size()) {
                    String actualCol = actualColumns.get(i);
                    if (actualRow.containsKey(actualCol)) {
                        value = actualRow.get(actualCol);
                    } else {
                        for (Map.Entry<String, Object> entry : actualRow.entrySet()) {
                            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(actualCol)) {
                                value = entry.getValue();
                                break;
                            }
                        }
                    }
                }
                newRow.put(expectedCol, value);
            }
            remapped.add(newRow);
        }
        return remapped;
    }

    private int countMissingColumns(List<String> expectedColumns, List<String> actualColumns) {
        if (expectedColumns == null || expectedColumns.isEmpty()) {
            return 0;
        }

        Set<String> actualSet = new HashSet<>();
        if (actualColumns != null) {
            for (String actual : actualColumns) {
                if (actual != null) {
                    actualSet.add(actual.toLowerCase(Locale.ROOT));
                }
            }
        }

        int missing = 0;
        for (String expected : expectedColumns) {
            if (expected == null) {
                continue;
            }
            if (!actualSet.contains(expected.toLowerCase(Locale.ROOT))) {
                missing++;
            }
        }
        return missing;
    }

    private int countExtraColumns(List<String> expectedColumns, List<String> actualColumns) {
        if (actualColumns == null || actualColumns.isEmpty()) {
            return 0;
        }

        Set<String> expectedSet = new HashSet<>();
        if (expectedColumns != null) {
            for (String expected : expectedColumns) {
                if (expected != null) {
                    expectedSet.add(expected.toLowerCase(Locale.ROOT));
                }
            }
        }

        int extra = 0;
        for (String actual : actualColumns) {
            if (actual == null) {
                continue;
            }
            if (!expectedSet.contains(actual.toLowerCase(Locale.ROOT))) {
                extra++;
            }
        }
        return extra;
    }

    private boolean sameColumnOrderIgnoreCase(List<String> expectedColumns, List<String> actualColumns) {
        if (expectedColumns == null || actualColumns == null) {
            return false;
        }
        if (expectedColumns.size() != actualColumns.size()) {
            return false;
        }
        for (int i = 0; i < expectedColumns.size(); i++) {
            String expected = expectedColumns.get(i);
            String actual = actualColumns.get(i);
            if (expected == null && actual == null) {
                continue;
            }
            if (expected == null || actual == null || !expected.equalsIgnoreCase(actual)) {
                return false;
            }
        }
        return true;
    }

    private int countSelectRowOrderViolations(
            List<Map<String, Object>> actualRows,
            List<Map<String, Object>> expectedRows,
            List<String> columns) {
        if (actualRows == null || expectedRows == null) {
            return 0;
        }

        int limit = Math.min(actualRows.size(), expectedRows.size());
        int violations = 0;
        for (int i = 0; i < limit; i++) {
            String actualSig = buildSelectRowSignature(actualRows.get(i), columns);
            String expectedSig = buildSelectRowSignature(expectedRows.get(i), columns);
            if (!actualSig.equals(expectedSig)) {
                violations++;
            }
        }
        return violations;
    }

    private String buildSelectRowSignature(Map<String, Object> row, List<String> columns) {
        if (row == null) {
            return "";
        }

        StringBuilder signature = new StringBuilder();
        if (columns != null && !columns.isEmpty()) {
            for (String column : columns) {
                signature.append(normalizeValue(getRowValueIgnoreCase(row, column))).append("|||");
            }
            return signature.toString().toLowerCase(Locale.ROOT);
        }

        for (Object value : row.values()) {
            signature.append(normalizeValue(value)).append("|||");
        }
        return signature.toString().toLowerCase(Locale.ROOT);
    }

    private List<SelectRowPair> buildSelectRowPairs(
            List<Map<String, Object>> actualRows,
            List<Map<String, Object>> expectedRows,
            List<String> columns,
            boolean strictOrdering,
            JsonNode cellModifiers) {
        if (actualRows == null || expectedRows == null || columns == null || columns.isEmpty()) {
            return List.of();
        }

        List<SelectRowPair> pairs = new ArrayList<>();
        if (strictOrdering) {
            int limit = Math.min(actualRows.size(), expectedRows.size());
            for (int i = 0; i < limit; i++) {
                pairs.add(new SelectRowPair(actualRows.get(i), expectedRows.get(i)));
            }
            return pairs;
        }

        List<Map<String, Object>> remainingActualRows = new ArrayList<>(actualRows);
        for (Map<String, Object> expectedRow : expectedRows) {
            int matchedIndex = findBestSelectRowMatchIndex(
                    remainingActualRows,
                    expectedRow,
                    columns,
                    cellModifiers);
            if (matchedIndex < 0) {
                continue;
            }

            Map<String, Object> matchedRow = remainingActualRows.remove(matchedIndex);
            pairs.add(new SelectRowPair(matchedRow, expectedRow));
        }

        return pairs;
    }

    private int findBestSelectRowMatchIndex(
            List<Map<String, Object>> actualRows,
            Map<String, Object> expectedRow,
            List<String> columns,
            JsonNode cellModifiers) {
        if (actualRows == null || actualRows.isEmpty()) {
            return -1;
        }

        int bestIndex = -1;
        int bestScore = -1;
        for (int i = 0; i < actualRows.size(); i++) {
            int score = scoreSelectRowMatch(actualRows.get(i), expectedRow, columns, cellModifiers);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private int scoreSelectRowMatch(
            Map<String, Object> actualRow,
            Map<String, Object> expectedRow,
            List<String> columns,
            JsonNode cellModifiers) {
        if (actualRow == null || expectedRow == null || columns == null || columns.isEmpty()) {
            return 0;
        }

        int score = 0;
        for (String column : columns) {
            Object actualValue = getRowValueIgnoreCase(actualRow, column);
            Object expectedValue = getRowValueIgnoreCase(expectedRow, column);
            if (valuesEqualByMatchTypeWithModifiers(
                    actualValue,
                    expectedValue,
                    "EXACT",
                    cellModifiers,
                    false,
                    false)) {
                score++;
            }
        }
        return score;
    }

    private int countSelectCellMismatches(
            List<SelectRowPair> rowPairs,
            List<String> columns,
            JsonNode cellModifiers) {
        if (rowPairs == null || rowPairs.isEmpty() || columns == null || columns.isEmpty()) {
            return 0;
        }

        int mismatches = 0;
        for (SelectRowPair rowPair : rowPairs) {
            for (String column : columns) {
                Object actualValue = getRowValueIgnoreCase(rowPair.actualRow(), column);
                Object expectedValue = getRowValueIgnoreCase(rowPair.expectedRow(), column);

                boolean equals = valuesEqualByMatchTypeWithModifiers(
                        actualValue,
                        expectedValue,
                        "EXACT",
                        cellModifiers,
                        false,
                        false);
                if (!equals) {
                    mismatches++;
                }
            }
        }

        return mismatches;
    }

    private int countSelectNullViolations(
            List<SelectRowPair> rowPairs,
            List<String> columns,
            JsonNode cellModifiers) {
        if (rowPairs == null || rowPairs.isEmpty() || columns == null || columns.isEmpty()) {
            return 0;
        }

        int nullViolations = 0;
        for (SelectRowPair rowPair : rowPairs) {
            for (String column : columns) {
                Object actualValue = getRowValueIgnoreCase(rowPair.actualRow(), column);
                Object expectedValue = getRowValueIgnoreCase(rowPair.expectedRow(), column);

                String normalizedActual = applyInsertModifiers(
                        normalizeValueStr(actualValue, false, false),
                        cellModifiers);
                String normalizedExpected = applyInsertModifiers(
                        normalizeValueStr(expectedValue, false, false),
                        cellModifiers);

                if (!isNullLike(normalizedExpected) && isNullLike(normalizedActual)) {
                    nullViolations++;
                }
            }
        }

        return nullViolations;
    }

    private SelectRuleApplication applySelectRule(
            JsonNode gradingRules,
            String target,
            String condition,
            int violationCount,
            BigDecimal datasetMaxPoints,
            double defaultPenaltyPerViolation,
            String violationSummary) {
        if (violationCount <= 0) {
            return SelectRuleApplication.noViolation();
        }

        JsonNode ruleNode = findInsertRule(gradingRules, target, condition);
        if (ruleNode == null) {
            return SelectRuleApplication.unmatchedViolation(target, condition, violationSummary);
        }

        SelectRuleDecision decision = resolveSelectRuleDecision(
                ruleNode,
                datasetMaxPoints,
                defaultPenaltyPerViolation);

        String ruleLabel = selectRuleLabel(target, condition);
        BigDecimal configuredPenalty = decision.failAll()
                ? datasetMaxPoints
                : BigDecimal.valueOf(Math.max(0d, decision.penaltyPerViolation()));
        if (decision.ignore()) {
            String message = String.format(
                    Locale.ROOT,
                    "Rule %s bỏ qua vi phạm (%s).",
                    ruleLabel,
                    violationSummary);
            return SelectRuleApplication.matchedViolation(
                    target,
                    condition,
                    decision.action(),
                    configuredPenalty,
                    false,
                    BigDecimal.ZERO,
                    message,
                    violationSummary);
        }

        if (decision.failAll()) {
            String message = String.format(
                    Locale.ROOT,
                    "Rule %s kích hoạt FAIL_ALL (%s).",
                    ruleLabel,
                    violationSummary);
            return SelectRuleApplication.matchedViolation(
                    target,
                    condition,
                    decision.action(),
                    configuredPenalty,
                    true,
                    BigDecimal.ZERO,
                    message,
                    violationSummary);
        }

        BigDecimal deduction = BigDecimal.valueOf(Math.max(0d, decision.penaltyPerViolation()))
                .multiply(BigDecimal.valueOf(violationCount));

        String message = buildSelectRuleMessage(
                ruleLabel,
                violationSummary,
                deduction,
                decision.action());
        return SelectRuleApplication.matchedViolation(
                target,
                condition,
                decision.action(),
                configuredPenalty,
                false,
                deduction,
                message,
                violationSummary);
    }

    private SelectRuleDecision resolveSelectRuleDecision(
            JsonNode ruleNode,
            BigDecimal datasetMaxPoints,
            double defaultPenaltyPerViolation) {
        String action = ruleNode != null ? ruleNode.path("action").asText("").trim() : "";
        if (action.isBlank()) {
            action = "DEDUCT_POINTS";
        }

        String normalizedAction = action.toUpperCase(Locale.ROOT);
        double safeDefaultPenalty = Math.max(0d, defaultPenaltyPerViolation);
        double penaltyValue = ruleNode != null
                ? readDoubleSetting(ruleNode.path("penalty_value"), -1d)
                : -1d;

        switch (normalizedAction) {
            case "IGNORE":
                return new SelectRuleDecision(normalizedAction, 0d, true, false);
            case "FAIL_ALL":
                return new SelectRuleDecision(normalizedAction, 0d, false, true);
            case "FAIL_ITEM":
                return new SelectRuleDecision(normalizedAction, safeDefaultPenalty, false, false);
            case "DEDUCT_PERCENTAGE": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, datasetMaxPoints.doubleValue() * penaltyValue / 100d)
                        : safeDefaultPenalty;
                return new SelectRuleDecision(normalizedAction, penalty, false, false);
            }
            case "DEDUCT_POINTS": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, penaltyValue)
                        : safeDefaultPenalty;
                return new SelectRuleDecision(normalizedAction, penalty, false, false);
            }
            default:
                return new SelectRuleDecision("DEDUCT_POINTS", safeDefaultPenalty, false, false);
        }
    }

    private String selectRuleLabel(String target, String condition) {
        return target.toUpperCase(Locale.ROOT) + "/" + condition.toUpperCase(Locale.ROOT);
    }

    private String buildSelectRuleMessage(
            String ruleLabel,
            String violationSummary,
            BigDecimal deduction,
            String action) {
        String formattedDeduction = deduction.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return String.format(
                Locale.ROOT,
                "Rule %s (%s, action=%s): trừ %s điểm.",
                ruleLabel,
                violationSummary,
                action,
                formattedDeduction);
    }

    private void addSelectRuleTrace(
            String caseId,
            String caseName,
            SelectRuleApplication application,
            BigDecimal maxPoints,
            String configSummary) {
        if (!GradingTraceCollector.isActive() || application == null || !application.violationPresent()) {
            return;
        }

        String target = application.target() == null ? "UNKNOWN" : application.target();
        String condition = application.condition() == null ? "UNKNOWN" : application.condition();
        String ruleLabel = selectRuleLabel(target, condition);
        String message = application.message();
        if (message == null || message.isBlank()) {
            message = "Phát hiện " + application.violationSummary()
                    + " nhưng không có rule " + ruleLabel + " tương ứng trong cấu hình.";
        }

        BigDecimal deductedPoints = application.failAllTriggered()
                ? maxPoints
                : application.deduction();
        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_RUBRIC_RULE,
                application.ruleMatched() ? GradingTraceItem.STATUS_FAIL : GradingTraceItem.STATUS_WARN,
                "Rule " + ruleLabel,
                message,
                caseId,
                caseName,
                target,
                condition,
                application.action(),
                application.configuredPenalty(),
                null,
                maxPoints,
                deductedPoints != null && deductedPoints.compareTo(BigDecimal.ZERO) > 0 ? deductedPoints : null,
                null,
                null,
                configSummary));
    }

    private void appendSelectIssue(StringBuilder builder, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(message.trim());
    }

    private record SelectExpectedRows(List<String> columns, List<Map<String, Object>> rows) {
    }

    private record SelectDatasetSpec(String datasetLabel, String datasetScript) {
    }

    private record SelectRowPair(Map<String, Object> actualRow, Map<String, Object> expectedRow) {
    }

    private record SelectRuleDecision(String action, double penaltyPerViolation, boolean ignore, boolean failAll) {
    }

    private record SelectRuleApplication(
            boolean violationPresent,
            boolean ruleMatched,
            boolean failAllTriggered,
            String target,
            String condition,
            String action,
            BigDecimal configuredPenalty,
            BigDecimal deduction,
            String message,
            String violationSummary) {
        static SelectRuleApplication noViolation() {
            return new SelectRuleApplication(false, false, false, null, null, null, null, BigDecimal.ZERO, null, null);
        }

        static SelectRuleApplication unmatchedViolation(String target, String condition, String violationSummary) {
            return new SelectRuleApplication(
                    true,
                    false,
                    false,
                    target,
                    condition,
                    null,
                    null,
                    BigDecimal.ZERO,
                    null,
                    violationSummary);
        }

        static SelectRuleApplication matchedViolation(
                String target,
                String condition,
                String action,
                BigDecimal configuredPenalty,
                boolean failAllTriggered,
                BigDecimal deduction,
                String message,
                String violationSummary) {
            return new SelectRuleApplication(
                    true,
                    true,
                    failAllTriggered,
                    target,
                    condition,
                    action,
                    configuredPenalty,
                    deduction,
                    message,
                    violationSummary);
        }
    }

    private record SelectDatasetDecision(
            boolean executionFailed,
            boolean allChecksPassed,
            boolean failAllTriggered,
            String errorMessage,
            BigDecimal earnedPoints) {
        static SelectDatasetDecision strictPass() {
            return new SelectDatasetDecision(false, true, false, null, BigDecimal.ZERO);
        }

        static SelectDatasetDecision strictMismatch(String message) {
            return new SelectDatasetDecision(false, false, false, message, BigDecimal.ZERO);
        }

        static SelectDatasetDecision executionFailure(String message) {
            return new SelectDatasetDecision(true, false, false, message, BigDecimal.ZERO);
        }

        static SelectDatasetDecision ruleResult(
                boolean allChecksPassed,
                boolean failAllTriggered,
                String errorMessage,
                BigDecimal earnedPoints) {
            return new SelectDatasetDecision(false, allChecksPassed, failAllTriggered, errorMessage, earnedPoints);
        }
    }

    private record GradeDecision(boolean isCorrect, String errorMessage, BigDecimal scoreEarned) {
        static GradeDecision pass(BigDecimal scoreEarned) {
            return new GradeDecision(true, null, scoreEarned == null ? BigDecimal.ZERO : scoreEarned);
        }

        static GradeDecision fail(String message) {
            return new GradeDecision(false, message, BigDecimal.ZERO);
        }

        static GradeDecision partial(BigDecimal scoreEarned, String message) {
            return new GradeDecision(false, message, scoreEarned == null ? BigDecimal.ZERO : scoreEarned);
        }
    }

    private void addTeacherConfigTrace(
            String status,
            String label,
            String message,
            BigDecimal maxPoints,
            String configSummary) {
        if (!GradingTraceCollector.isActive()) {
            return;
        }

        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_TEACHER_CONFIG,
                status,
                label,
                message,
                null,
                null,
                "TEACHER_CONFIG",
                "MISSING",
                "REVIEW_CONFIG",
                null,
                null,
                maxPoints,
                null,
                null,
                null,
                configSummary));
    }

    private boolean gradeByTestCases(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission) {
        List<TestCase> testCases = testCaseRepository.findByQuestionId(question.getId());
        log.info("[gradeByTestCases] Câu {} có {} test case", question.getId(),
                testCases == null ? 0 : testCases.size());

        if (testCases == null || testCases.isEmpty()) {
            // Fail-loud for DDL types: surface the "missing rubric/test cases" reason
            // on the submission so the teacher knows the question needs setup.
            QuestionType type = question.getQuestionType();
            BigDecimal questionPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
            addTeacherConfigTrace(
                    GradingTraceItem.STATUS_WARN,
                    "Thiếu test case",
                    "[THIẾU TEST CASE] Câu hỏi này chưa có test case nào trong DB.",
                    questionPoints,
                    "Không có test case cho " + type + "; hệ thống fallback sang strict comparison nếu có thể.");
            if (submission != null && (type == QuestionType.STORED_PROCEDURE
                    || type == QuestionType.FUNCTION
                    || type == QuestionType.TRIGGER)) {
                submission.setErrorMessage(
                        "[THIẾU TEST CASE] Câu hỏi này chưa có test case nào trong DB. "
                                + "Hãy chạy pipeline tạo rubric (T08/T09) hoặc thêm test case thủ công. "
                                + "Điểm hiện tại chỉ phản ánh phần kiểm tra metadata.");
            }
            return gradeByStrictComparison(schemaName, question);
        }

        boolean useDeductionScoring = question.getQuestionType() == QuestionType.STORED_PROCEDURE;
        BigDecimal earnedTotal = useDeductionScoring ? BigDecimal.ONE : BigDecimal.ZERO;
        boolean allPassed = true;
        StringBuilder errorBuilder = new StringBuilder();
        String printOutputCompareMode = readPrintOutputCompareMode(question);

        for (TestCase tc : testCases) {
            int tcOrder = tc.getOrderIndex() != null ? tc.getOrderIndex() : 0;
            BigDecimal caseWeight = tc.getScoreWeight() != null ? tc.getScoreWeight() : BigDecimal.ZERO;
            try {
                TestCaseRunResult run = runOneTestCase(schemaName, teacherSchemaName, tc);
                String actualSerialized = run.actualValue;
                String expected = tc.getExpectedValue() != null ? tc.getExpectedValue().trim() : "";

                boolean isTcCorrect = compareWithMatchType(actualSerialized, expected, tc, printOutputCompareMode);

                log.info("[gradeByTestCases] Câu {} TC{} ({}): thực tế='{}' mong đợi='{}' khớp={}",
                        question.getId(), tcOrder,
                        tc.getVerificationType(),
                        truncateForLog(actualSerialized), truncateForLog(expected), isTcCorrect);

                if (isTcCorrect) {
                    if (!useDeductionScoring) {
                        earnedTotal = earnedTotal.add(caseWeight);
                    }
                } else {
                    allPassed = false;
                    if (useDeductionScoring) {
                        earnedTotal = earnedTotal.subtract(caseWeight);
                    }
                    String tcLabel = tc.getCaseName() != null ? tc.getCaseName() : "TC" + tcOrder;
                errorBuilder.append(String.format("[%s] mong đợi='%s', thực tế='%s'. ",
                            tcLabel, truncateForLog(expected), truncateForLog(actualSerialized)));
                }
                if (GradingTraceCollector.isActive()) {
                    String tcLabel = tc.getCaseName() != null ? tc.getCaseName() : "TC" + tcOrder;
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_TEST_CASE,
                            isTcCorrect ? GradingTraceItem.STATUS_PASS : GradingTraceItem.STATUS_FAIL,
                            tcLabel,
                            isTcCorrect ? "Test case đạt" : "Kết quả không khớp",
                            tc.getId() != null ? tc.getId().toString() : null,
                            tcLabel,
                            null, null, null, null,
                            isTcCorrect ? caseWeight : BigDecimal.ZERO,
                            caseWeight,
                            isTcCorrect ? null : caseWeight,
                            truncateForLog(expected),
                            truncateForLog(actualSerialized),
                            (tc.getVerificationType() != null ? tc.getVerificationType().name() : "")
                                    + " (trọng số, điểm tuyệt đối = trọng số × điểm câu × tỷ lệ TC)"));
                }
            } catch (Exception e) {
                allPassed = false;
                if (useDeductionScoring) {
                    earnedTotal = earnedTotal.subtract(caseWeight);
                }
                String tcLabel = tc.getCaseName() != null ? tc.getCaseName() : "TC" + tcOrder;
                errorBuilder.append(String.format("[%s] lỗi khi chạy test case: %s. ", tcLabel, e.getMessage()));
                log.error("[gradeByTestCases] Câu {} TC{} phát sinh lỗi: {}",
                        question.getId(), tcOrder, e.getMessage(), e);
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_TEST_CASE,
                            GradingTraceItem.STATUS_FAIL,
                            tcLabel,
                            "Lỗi khi chạy test case: " + e.getMessage(),
                            tc.getId() != null ? tc.getId().toString() : null,
                            tcLabel,
                            null, null, null, null,
                            BigDecimal.ZERO, caseWeight, caseWeight,
                            null, null,
                            (tc.getVerificationType() != null ? tc.getVerificationType().name() : "")
                                    + " (trọng số, điểm tuyệt đối = trọng số × điểm câu × tỷ lệ TC)"));
                }
            }
        }
        if (earnedTotal.compareTo(BigDecimal.ZERO) < 0) {
            earnedTotal = BigDecimal.ZERO;
        } else if (earnedTotal.compareTo(BigDecimal.ONE) > 0) {
            earnedTotal = BigDecimal.ONE;
        }
        log.info("[gradeByTestCases] Câu {} tất cả đạt={} tổng trọng số đạt={}", question.getId(), allPassed,
                earnedTotal);

        if (submission != null) {
            submission.setScoreEarned(earnedTotal);
            if (!allPassed) {
                submission.setErrorMessage(errorBuilder.toString().trim());
            }
        }

        return allPassed;
    }

    /**
     * Runs one test case against the student's schema, dispatching by
     * {@link VerificationType}. The sequence setup → invocation → validation is
     * executed inside a SQL Server BEGIN TRAN / ROLLBACK TRAN block so that any
     * INSERT/UPDATE/DELETE side effects (especially common for SIDE_EFFECT and
     * Trigger TC's) do not leak to the next TC.
     *
     * <p>Note on connection: ROLLBACK must run on the same connection as BEGIN
     * TRAN. We achieve that by sending the whole script as ONE batch via
     * executeSqlBatchAsSchemaUser (a single jdbcTemplate.execute call uses one
     * connection). The validation_query's result set is read by the engine
     * BEFORE the ROLLBACK statement clears it — standard MSSQL pattern.
     *
     * <p>Note on impersonation (P0-2): the batch runs under EXECUTE AS USER for
     * the student's schema-scoped DB user, NOT under the admin connection. This
     * is what stops a malicious or buggy student SP from reading other
     * students' schemas. executeSqlBatchAsSchemaUser also enforces
     * QUERY_TIMEOUT_SECONDS (P0-3) so an infinite loop / WAITFOR cannot hang
     * the grading worker.
     */
    private TestCaseRunResult runOneTestCase(String schemaName, String teacherSchemaName, TestCase tc) {
        VerificationType type = tc.getVerificationType() != null
                ? tc.getVerificationType()
                : VerificationType.RETURN_VALUE;

        String setup = applyPlaceholders(tc.getSetupScript(), schemaName, teacherSchemaName);
        String invocation = applyPlaceholders(tc.getInvocationQuery(), schemaName, teacherSchemaName);
        String validation = applyPlaceholders(tc.getValidationQuery(), schemaName, teacherSchemaName);

        // Build a single SQL batch that wraps the whole TC in a transaction.
        // Why TRY/CATCH: if any inner statement throws, we still want a clean
        // ROLLBACK and a thrown exception (the catch re-throws via THROW).
        StringBuilder batch = new StringBuilder();
        batch.append("BEGIN TRY\n");
        batch.append("  BEGIN TRANSACTION;\n");
        if (setup != null && !setup.isBlank()) {
            batch.append("  ").append(setup).append(";\n");
        }
        if (invocation != null && !invocation.isBlank()) {
            batch.append("  ").append(invocation).append(";\n");
        }
        if (validation != null && !validation.isBlank()) {
            // P1-2: emit a marker result set right before validation_query.
            // If invocation_query unintentionally produced result sets (e.g. an
            // SP whose body has SELECT statements), executeSqlBatchAsSchemaUser
            // would concatenate them together with validation rows. The marker
            // lets us drop everything before validation when serializing.
            batch.append("  SELECT NULL AS ").append(VALIDATION_MARKER_COLUMN).append(";\n");
            batch.append("  ").append(validation).append(";\n");
        }
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append("END TRY\n");
        batch.append("BEGIN CATCH\n");
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append("  THROW;\n");
        batch.append("END CATCH;");

        // Run as the student's schema-scoped DB user (NOT admin) so that any
        // student-defined routine called inside the batch is restricted to its
        // own schema's permissions. Also enforces query timeout.
        SqlExecutionResult execResult = examSchemaService.executeSqlBatchAsSchemaUser(schemaName, batch.toString());

        // Capture per verification_type — must match ExpectedValueDeriver.serializeResult
        // exactly so EXACT compare works.
        String actual;
        if (type == VerificationType.PRINT_OUTPUT) {
            List<String> prints = execResult.getPrintMessages() != null
                    ? execResult.getPrintMessages()
                    : new ArrayList<>();
            actual = String.join("\n", prints).trim();
        } else {
            actual = serializeResultForCompare(dropRowsBeforeValidationMarker(execResult));
        }
        return new TestCaseRunResult(actual);
    }

    /** Column name used to mark the start of validation_query's result set. */
    private static final String VALIDATION_MARKER_COLUMN = "__VALIDATION_MARKER__";

    /**
     * Returns a copy of {@code execResult} with all rows up to AND including the
     * marker row removed. If no marker row is present (e.g. test case has no
     * validation_query, or marker was added by a different layer), returns the
     * original result unchanged.
     */
    private SqlExecutionResult dropRowsBeforeValidationMarker(SqlExecutionResult execResult) {
        if (execResult == null || execResult.getResultSet() == null) {
            return execResult;
        }
        List<Map<String, Object>> rows = execResult.getResultSet();
        int markerIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).containsKey(VALIDATION_MARKER_COLUMN)) {
                markerIdx = i;
                break;
            }
        }
        if (markerIdx < 0) {
            return execResult;
        }
        List<Map<String, Object>> filtered = new ArrayList<>(rows.subList(markerIdx + 1, rows.size()));
        return SqlExecutionResult.builder()
                .resultSet(filtered)
                .rowCount(filtered.size())
                .statusMessage(execResult.getStatusMessage())
                .printMessages(execResult.getPrintMessages())
                .build();
    }

    /**
     * Mirrors ExpectedValueDeriver.serializeResult — both must agree on the
     * canonical string form, otherwise EXACT compare always fails.
     */
    private String serializeResultForCompare(SqlExecutionResult result) {
        if (result == null || result.getResultSet() == null || result.getResultSet().isEmpty()) {
            return "";
        }
        List<Map<String, Object>> rows = result.getResultSet();
        if (rows.size() == 1 && rows.get(0).size() == 1) {
            Object v = rows.get(0).values().iterator().next();
            return v == null ? "null" : v.toString().trim();
        }
        List<String> rowStrs = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object v : row.values()) {
                if (!first) sb.append("|");
                sb.append(v == null ? "null" : v.toString().trim());
                first = false;
            }
            rowStrs.add(sb.toString());
        }
        Collections.sort(rowStrs);
        return String.join("\n", rowStrs);
    }

    private boolean compareWithMatchType(String actual, String expected, TestCase tc, String printOutputCompareMode) {
        if (actual == null) actual = "";
        if (expected == null) expected = "";
        actual = actual.trim();
        expected = expected.trim();

        if (tc.getVerificationType() == VerificationType.PRINT_OUTPUT
                && "LENIENT".equalsIgnoreCase(printOutputCompareMode)) {
            actual = normalizePrintOutputForCompare(actual);
            expected = normalizePrintOutputForCompare(expected);
        }

        graduation_project_be.domain.models.enums.MatchType match = tc.getMatchType() != null
                ? tc.getMatchType()
                : graduation_project_be.domain.models.enums.MatchType.EXACT;
        if (tc.getVerificationType() == VerificationType.PRINT_OUTPUT
                && match == graduation_project_be.domain.models.enums.MatchType.CONTAINS) {
            return actual.toLowerCase().contains(expected.toLowerCase());
        }
        // EXACT (default)
        return actual.equalsIgnoreCase(expected);
    }

    private String readPrintOutputCompareMode(ExamQuestion question) {
        if (question == null || question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return "LENIENT";
        }
        try {
            JsonNode root = objectMapper.readTree(question.getGradingRubric());
            return root.path("grading_payload")
                    .path("grading_settings")
                    .path("print_output_compare_mode")
                    .asText("LENIENT");
        } catch (Exception e) {
            log.warn("Không thể phân tích print_output_compare_mode cho câu {}: {}", question.getId(), e.getMessage());
            return "LENIENT";
        }
    }

    private String normalizePrintOutputForCompare(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String applyPlaceholders(String sql, String schemaName, String teacherSchemaName) {
        if (sql == null) return null;
        String result = sql;
        if (schemaName != null) result = result.replace("{SCHEMA}", schemaName);
        if (teacherSchemaName != null) result = result.replace("{TEACHER_SCHEMA}", teacherSchemaName);
        // [dbo] / dbo. is a recurring AI hallucination from REFERENCE SQL.
        // The grading engine runs every batch inside a per-user schema, never dbo,
        // so hardcoded dbo always fails with "Could not find stored procedure".
        // Coerce to the target schema; safe because no question in this system
        // intentionally targets dbo objects.
        if (schemaName != null) {
            result = result.replaceAll("(?i)\\[dbo\\]\\s*\\.", "[" + schemaName + "].");
            result = result.replaceAll("(?i)\\bdbo\\s*\\.", "[" + schemaName + "].");
        }
        return result;
    }

    private static String truncateForLog(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private record TestCaseRunResult(String actualValue) {
    }

    private boolean gradeByStrictComparison(String schemaName, ExamQuestion question) {
        // Fail-loud for DDL-style questions. correctQuery for these types is
        // CREATE PROC/FUNCTION/TRIGGER — re-running it on the student schema
        // either throws "object already exists" (if the student got it right)
        // or returns no result set (which we'd interpret as wrong answer).
        // Either way, strict comparison is the wrong semantics for DDL.
        // Instead we surface a clear "missing rubric/test cases" message so the
        // teacher knows to add test cases for this question.
        QuestionType type = question.getQuestionType();
        if (type == QuestionType.STORED_PROCEDURE
                || type == QuestionType.FUNCTION
                || type == QuestionType.TRIGGER) {
            log.error("[gradeByStrictComparison] Câu {} ({}) không có test case và không có rubric dùng được — "
                    + "không thể fallback sang so sánh nghiêm ngặt cho loại DDL. Điểm sẽ dựa trên "
                    + "kiểm tra chỉ metadata (và có thể là 0 nếu metadata cũng thiếu).",
                    question.getId(), type);
            return false;
        }

        try {
            List<Map<String, Object>> actual = examSchemaService.executeSql(
                    schemaName, question.getCorrectQuery()).getResultSet();

            String verifyScript = question.getVerifyScript();
            if (verifyScript == null || verifyScript.isBlank()) {
                log.warn("Câu {} không có verify_script nên không thể so sánh nghiêm ngặt", question.getId());
                return actual != null && !actual.isEmpty();
            }

            List<Map<String, Object>> expected = examSchemaService.executeSql(
                    schemaName, verifyScript).getResultSet();

            boolean requireStrictOrder = question.getCorrectQuery() != null
                    && question.getCorrectQuery().toUpperCase().contains("ORDER BY");

            return compareResultSetsStrict(actual, expected, requireStrictOrder);
        } catch (Exception e) {
            log.warn("Chấm so sánh nghiêm ngặt thất bại cho câu {}: {}", question.getId(), e.getMessage());
            return false;
        }
    }

    private boolean compareResultSetsStrict(List<Map<String, Object>> actual,
            List<Map<String, Object>> expected, boolean requireStrictOrder) {
        if (actual == null || expected == null)
            return false;
        if (actual.size() != expected.size())
            return false;

        List<String> actualRows = new ArrayList<>();
        for (Map<String, Object> row : actual) {
            StringBuilder sb = new StringBuilder();
            for (Object val : row.values()) {
                sb.append(normalizeValue(val)).append("|||");
            }
            actualRows.add(sb.toString().toLowerCase());
        }

        List<String> expectedRows = new ArrayList<>();
        for (Map<String, Object> row : expected) {
            StringBuilder sb = new StringBuilder();
            for (Object val : row.values()) {
                sb.append(normalizeValue(val)).append("|||");
            }
            expectedRows.add(sb.toString().toLowerCase());
        }

        if (!requireStrictOrder) {
            Collections.sort(actualRows);
            Collections.sort(expectedRows);
        }

        return actualRows.equals(expectedRows);
    }

    private String normalizeValue(Object value) {
        if (value == null)
            return "null";
        String str = value.toString().trim();
        if (str.matches("-?\\d+\\.\\d+")) {
            str = str.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return str;
    }

    private boolean gradeRoutineAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission) {
        // T11/T12: setup_script is now applied per-test-case inside a transaction
        // (see gradeByTestCases.runOneTestCase). The previous upfront concatenation
        // here is removed because:
        //  1. it ran with literal {SCHEMA} placeholders (not substituted),
        //     causing MSSQL parse errors with the new prompt convention.
        //  2. concatenating all setups across TC's leaked state across TC's.
        //  3. MSSQL does deferred name resolution for CREATE PROCEDURE/FUNCTION/
        //     TRIGGER bodies, so referenced tables don't need to exist at CREATE
        //     time — only at invocation time, which happens inside per-TC TX.

        // Source of truth for "what routines the question requires" is the AI
        // rubric, not the teacher schema. Teacher's correctQuery may include
        // helper FNs/SPs as implementation detail (e.g. an FN_NextId helper
        // called from inside the main SP); the question itself only asks the
        // student to create the user-facing routine. Penalizing students for
        // not creating those helpers would be unfair — they may inline the
        // logic, use a CTE, or take a different approach entirely.
        // See md/GRAD-141_SP_GRADING_GAPS.md §2 LỖ HỔNG 1.
        List<RoutineMetadata> expectedRoutines =
                extractExpectedRoutinesFromRubric(question.getGradingRubric());
        List<RoutineMetadata> teacherSchemaRoutines =
                examSchemaService.extractRoutineMetadata(teacherSchemaName);
        if (expectedRoutines == null || expectedRoutines.isEmpty()) {
            // Legacy questions (created before the rubric was authoritative
            // for routines[]) fall back to teacher schema metadata.
            expectedRoutines = teacherSchemaRoutines;
        } else {
            logHelperRoutinesIgnored(question.getId(), expectedRoutines, teacherSchemaRoutines);
        }
        List<RoutineMetadata> actualRoutines = examSchemaService.extractRoutineMetadata(schemaName);

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        boolean hasTestCases = !testCaseRepository.findByQuestionId(question.getId()).isEmpty();

        // If teacher schema has no routines (DDL-only), grade purely by test cases
        // (100% weight)
        if (expectedRoutines == null || expectedRoutines.isEmpty()) {
            if (!hasTestCases) {
                // Nothing to grade against
                String message = "[THIẾU CẤU HÌNH] Câu hỏi không có metadata routine trong rubric/teacher schema "
                        + "và cũng không có test case.";
                log.warn("Câu {} không có metadata của routine và cũng không có test case", question.getId());
                addTeacherConfigTrace(
                        GradingTraceItem.STATUS_FAIL,
                        "Thiếu routine metadata/test case",
                        message,
                        totalPoints,
                        "Không có routines[] trong rubric, teacher schema không có routine, và DB không có test case.");
                if (submission != null) {
                    submission.setScoreEarned(BigDecimal.ZERO);
                    submission.setErrorMessage(message);
                }
                return false;
            }
            boolean passed = gradeByTestCases(schemaName, teacherSchemaName, question, submission);
            // gradeByTestCases returns a normalized test-case ratio. SP uses deduction
            // scoring (1 - failed weights); other routine types still use additive
            // scoring.
            if (submission != null && submission.getScoreEarned() != null) {
                BigDecimal scaled = totalPoints.multiply(submission.getScoreEarned());
                if (scaled.compareTo(totalPoints) > 0)
                    scaled = totalPoints;
                submission.setScoreEarned(scaled);
            }
            return passed;
        }

        // Stored procedure metadata is diagnostic when test cases exist; business
        // behavior is scored entirely by test cases. Functions keep the legacy
        // 20/80 split because they share this routine grader but are not part of
        // the current SP-only rubric change.
        boolean metadataDiagnosticOnly = hasTestCases
                && question.getQuestionType() == QuestionType.STORED_PROCEDURE;
        BigDecimal metadataWeight = metadataDiagnosticOnly
                ? BigDecimal.ZERO
                : (hasTestCases ? new BigDecimal("0.20") : BigDecimal.ONE);
        BigDecimal testCaseWeight = metadataDiagnosticOnly
                ? BigDecimal.ONE
                : (hasTestCases ? new BigDecimal("0.80") : BigDecimal.ZERO);

        BigDecimal maxMetadataScore = totalPoints.multiply(metadataWeight);
        BigDecimal perRoutineMax = maxMetadataScore.divide(BigDecimal.valueOf(expectedRoutines.size()), 4,
                RoundingMode.HALF_UP);
        BigDecimal earnedMetadataScore = BigDecimal.ZERO;

        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassedMetadata = true;

        for (RoutineMetadata expected : expectedRoutines) {
            RoutineMetadata actual = actualRoutines.stream()
                    .filter(r -> r.getRoutineName().equalsIgnoreCase(expected.getRoutineName()))
                    .findFirst().orElse(null);

            if (actual == null) {
                allPassedMetadata = false;
                errorBuilder
                        .append(String.format("Thiếu %s %s. ", expected.getRoutineType(), expected.getRoutineName()));
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_METADATA_CHECK, GradingTraceItem.STATUS_FAIL,
                            "Thiếu " + expected.getRoutineType(),
                            String.format("Thiếu %s %s", expected.getRoutineType(), expected.getRoutineName()),
                            null, null,
                            "ROUTINE", "EXISTS", null, null,
                            BigDecimal.ZERO, perRoutineMax, perRoutineMax,
                            expected.getRoutineName(), null,
                            "Kiểm tra sự tồn tại của " + expected.getRoutineType()));
                }
                continue;
            }

            double score = 0.5; // Found it

            // Type check (Procedure vs Function).
            // AI rubric ghi "STORED_PROCEDURE" theo enum domain trong khi
            // INFORMATION_SCHEMA.ROUTINES.ROUTINE_TYPE trả "PROCEDURE" — normalize
            // hai bên trước khi so để không log "Sai loại Routine" oan.
            if (normalizeRoutineType(expected.getRoutineType())
                    .equalsIgnoreCase(normalizeRoutineType(actual.getRoutineType()))) {
                score += 0.2;
            } else {
                errorBuilder.append(String.format("Sai loại routine %s (kỳ vọng: %s). ", expected.getRoutineName(),
                        expected.getRoutineType()));
                allPassedMetadata = false;
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_METADATA_CHECK, GradingTraceItem.STATUS_FAIL,
                            "Sai loại routine",
                            String.format("Sai loại routine %s (kỳ vọng: %s)", expected.getRoutineName(), expected.getRoutineType()),
                            null, null, "ROUTINE_TYPE", "MISMATCH", null, null,
                            null, null, null,
                            expected.getRoutineType(), actual.getRoutineType(),
                            "Kiểm tra loại routine"));
                }
            }

            // Params check
            if (expected.getParameters().size() == actual.getParameters().size()) {
                score += 0.3;
            } else {
                errorBuilder.append(String.format("%s %s sai số lượng tham số. ", expected.getRoutineType(),
                        expected.getRoutineName()));
                allPassedMetadata = false;
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_METADATA_CHECK, GradingTraceItem.STATUS_FAIL,
                            "Sai số tham số",
                            String.format("%s %s sai số lượng tham số (kỳ vọng: %d, thực tế: %d)",
                                    expected.getRoutineType(), expected.getRoutineName(),
                                    expected.getParameters().size(), actual.getParameters().size()),
                            null, null, "ROUTINE_PARAMS", "COUNT_MISMATCH", null, null,
                            null, null, null,
                            String.valueOf(expected.getParameters().size()), String.valueOf(actual.getParameters().size()),
                            "Kiểm tra số tham số routine"));
                }
            }

            earnedMetadataScore = earnedMetadataScore.add(perRoutineMax.multiply(BigDecimal.valueOf(score)));
        }

        if (allPassedMetadata)
            earnedMetadataScore = maxMetadataScore;

        if (GradingTraceCollector.isActive() && !allPassedMetadata) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_SUMMARY, GradingTraceItem.STATUS_FAIL,
                    "Tổng kết metadata routine",
                    errorBuilder.toString().trim(),
                    null, null, null, null, null, null,
                    earnedMetadataScore, maxMetadataScore,
                    maxMetadataScore.subtract(earnedMetadataScore),
                    null, null,
                    metadataDiagnosticOnly ? "Metadata chỉ mang tính chẩn đoán (100% TC scoring)" : "Metadata 20% + TC 80%"));
        }

        BigDecimal earnedTestCaseScore = BigDecimal.ZERO;
        boolean testCasesPassed = true;

        if (hasTestCases) {
            testCasesPassed = gradeByTestCases(schemaName, teacherSchemaName, question, submission);
            // gradeByTestCases returns a normalized test-case ratio. SP uses deduction
            // scoring (1 - failed weights); Function keeps additive scoring.
            if (submission != null && submission.getScoreEarned() != null) {
                BigDecimal maxTestCaseScore = totalPoints.multiply(testCaseWeight);
                earnedTestCaseScore = maxTestCaseScore.multiply(submission.getScoreEarned());
            }
        }

        BigDecimal finalScore = metadataDiagnosticOnly
                ? earnedTestCaseScore
                : earnedMetadataScore.add(earnedTestCaseScore);
        if (finalScore.compareTo(totalPoints) > 0)
            finalScore = totalPoints;

        if (submission != null) {
            submission.setScoreEarned(finalScore);
            boolean totalPassed = metadataDiagnosticOnly
                    ? testCasesPassed
                    : allPassedMetadata && testCasesPassed;
            if (!totalPassed) {
                String tcError = submission.getErrorMessage() != null ? submission.getErrorMessage() : "";
                submission.setErrorMessage((errorBuilder.toString().trim() + " " + tcError).trim());
            }
        }

        return metadataDiagnosticOnly ? testCasesPassed : allPassedMetadata && testCasesPassed;
    }

    private boolean gradeTriggerAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission) {
        // T11/T12: setup_script applied per-test-case inside a transaction.
        // See note in gradeRoutineAlgorithmic — same reasoning applies here.

        java.util.List<TriggerMetadata> expectedTriggers = examSchemaService.extractTriggerMetadata(teacherSchemaName);
        java.util.List<TriggerMetadata> actualTriggers = examSchemaService.extractTriggerMetadata(schemaName);

        if (expectedTriggers == null || expectedTriggers.isEmpty()) {
            return gradeByTestCases(schemaName, teacherSchemaName, question, submission);
        }

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        boolean hasTestCases = !testCaseRepository.findByQuestionId(question.getId()).isEmpty();

        BigDecimal metadataWeight = hasTestCases ? new BigDecimal("0.20") : BigDecimal.ONE;
        BigDecimal testCaseWeight = hasTestCases ? new BigDecimal("0.80") : BigDecimal.ZERO;

        BigDecimal maxMetadataScore = totalPoints.multiply(metadataWeight);
        BigDecimal perTriggerMax = maxMetadataScore.divide(BigDecimal.valueOf(expectedTriggers.size()), 4,
                RoundingMode.HALF_UP);
        BigDecimal earnedMetadataScore = BigDecimal.ZERO;

        StringBuilder errorBuilder = new StringBuilder();
        boolean allPassedMetadata = true;

        for (TriggerMetadata expected : expectedTriggers) {
            TriggerMetadata actual = actualTriggers.stream()
                    .filter(t -> t.getTriggerName().equalsIgnoreCase(expected.getTriggerName()))
                    .findFirst().orElse(null);

            if (actual == null) {
                allPassedMetadata = false;
                errorBuilder.append(String.format("Thiếu Trigger %s trên bảng %s. ", expected.getTriggerName(),
                        expected.getTableName()));
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_METADATA_CHECK, GradingTraceItem.STATUS_FAIL,
                            "Thiếu Trigger",
                            String.format("Thiếu Trigger %s trên bảng %s", expected.getTriggerName(), expected.getTableName()),
                            null, null,
                            "TRIGGER", "EXISTS", null, null,
                            BigDecimal.ZERO, perTriggerMax, perTriggerMax,
                            expected.getTriggerName(), null,
                            "Kiểm tra sự tồn tại của Trigger"));
                }
                continue;
            }

            double score = 0.4;

            if (actual.getTableName().equalsIgnoreCase(expected.getTableName()))
                score += 0.2;
            else {
                allPassedMetadata = false;
                errorBuilder.append(String.format("Trigger %s gắn sai bảng. ", expected.getTriggerName()));
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_METADATA_CHECK, GradingTraceItem.STATUS_FAIL,
                            "Trigger sai bảng",
                            String.format("Trigger %s gắn sai bảng (kỳ vọng: %s, thực tế: %s)",
                                    expected.getTriggerName(), expected.getTableName(), actual.getTableName()),
                            null, null, "TRIGGER_TABLE", "MISMATCH", null, null,
                            null, null, null,
                            expected.getTableName(), actual.getTableName(),
                            "Kiểm tra bảng gắn trigger"));
                }
            }

            if (expected.isInsert() == actual.isInsert() && expected.isUpdate() == actual.isUpdate()
                    && expected.isDelete() == actual.isDelete()) {
                score += 0.2;
            } else {
                allPassedMetadata = false;
                errorBuilder.append(
                        String.format("Trigger %s bắt sai sự kiện (INSERT/UPDATE/DELETE). ", expected.getTriggerName()));
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_METADATA_CHECK, GradingTraceItem.STATUS_FAIL,
                            "Trigger sai sự kiện",
                            String.format("Trigger %s bắt sai sự kiện (INSERT/UPDATE/DELETE)", expected.getTriggerName()),
                            null, null, "TRIGGER_EVENT", "MISMATCH", null, null,
                            null, null, null,
                            null, null,
                            "Kiểm tra sự kiện trigger"));
                }
            }

            if (expected.isAfter() == actual.isAfter()) {
                score += 0.2;
            } else {
                allPassedMetadata = false;
                errorBuilder
                        .append(String.format("Trigger %s sai thời điểm chạy (AFTER/INSTEAD OF). ", expected.getTriggerName()));
                if (GradingTraceCollector.isActive()) {
                    GradingTraceCollector.add(new GradingTraceItem(
                            GradingTraceItem.KIND_METADATA_CHECK, GradingTraceItem.STATUS_FAIL,
                            "Trigger sai thời điểm",
                            String.format("Trigger %s sai thời điểm chạy (AFTER/INSTEAD OF)", expected.getTriggerName()),
                            null, null, "TRIGGER_TIMING", "MISMATCH", null, null,
                            null, null, null,
                            null, null,
                            "Kiểm tra thời điểm trigger"));
                }
            }

            earnedMetadataScore = earnedMetadataScore.add(perTriggerMax.multiply(BigDecimal.valueOf(score)));
        }

        if (allPassedMetadata)
            earnedMetadataScore = maxMetadataScore;

        BigDecimal earnedTestCaseScore = BigDecimal.ZERO;
        boolean testCasesPassed = true;

        if (hasTestCases) {
            testCasesPassed = gradeByTestCases(schemaName, teacherSchemaName, question, submission);
            // gradeByTestCases sets scoreEarned as sum of normalized scoreWeights
            // (range 0..1). Multiply by maxTestCaseScore (= totalPoints * testCaseWeight)
            // to get the absolute points contribution. Same formula as gradeRoutineAlgorithmic.
            // BUG FIX (P0-1): previous code did scoreEarned * testCaseWeight which
            // missed the totalPoints factor, capping trigger TC contribution at 0.8
            // regardless of points (e.g. 10-point trigger with 100% TC pass yielded
            // 0.8 instead of 8).
            if (submission != null && submission.getScoreEarned() != null) {
                BigDecimal maxTestCaseScore = totalPoints.multiply(testCaseWeight);
                earnedTestCaseScore = maxTestCaseScore.multiply(submission.getScoreEarned());
            }
        }

        BigDecimal finalScore = earnedMetadataScore.add(earnedTestCaseScore);
        if (finalScore.compareTo(totalPoints) > 0)
            finalScore = totalPoints;

        if (submission != null) {
            submission.setScoreEarned(finalScore);
            boolean totalPassed = allPassedMetadata && testCasesPassed;
            if (!totalPassed) {
                String tcError = submission.getErrorMessage() != null ? submission.getErrorMessage() : "";
                submission.setErrorMessage((errorBuilder.toString().trim() + " " + tcError).trim());
            }
        }

        return allPassedMetadata && testCasesPassed;
    }

    private void setAllConstraintsEnabled(String schemaName, boolean enabled) {
        String safeSchema = schemaName.replaceAll("[^a-zA-Z0-9_]", "");
        List<Map<String, Object>> tables = examSchemaService.executeAdminSql(
                "SELECT t.name AS TABLE_NAME "
                        + "FROM sys.tables t "
                        + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
                        + "WHERE s.name = '" + safeSchema + "'")
                .getResultSet();

        for (Map<String, Object> row : tables) {
            Object tableNameObj = row.get("TABLE_NAME");
            if (tableNameObj == null) {
                continue;
            }

            String tableName = String.valueOf(tableNameObj).replaceAll("[^a-zA-Z0-9_]", "");
            String sql = enabled
                    ? "ALTER TABLE [" + safeSchema + "].[" + tableName + "] WITH CHECK CHECK CONSTRAINT ALL"
                    : "ALTER TABLE [" + safeSchema + "].[" + tableName + "] NOCHECK CONSTRAINT ALL";

            try {
                examSchemaService.executeAdminSql(sql);
            } catch (Exception e) {
                log.warn("Không thể {} ràng buộc cho bảng {}: {}",
                        enabled ? "bật" : "tắt", tableName, e.getMessage());
            }
        }
    }

    /**
     * Parses {@code grading_payload.routines[]} from the rubric JSON into
     * RoutineMetadata. Returns an empty list when the rubric is missing,
     * malformed, or omits the routines array — caller falls back to teacher
     * schema metadata in that case.
     */
    /**
     * Normalize routine type to a canonical form ("PROCEDURE" / "FUNCTION") so
     * that the rubric-side label ("STORED_PROCEDURE" coming from the AI / enum
     * domain) compares equal with the metadata-side label coming from
     * INFORMATION_SCHEMA.ROUTINES.ROUTINE_TYPE ("PROCEDURE" / "FUNCTION").
     */
    private static String normalizeRoutineType(String raw) {
        if (raw == null) return "";
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("STORED_PROCEDURE".equals(upper) || "SQL_STORED_PROCEDURE".equals(upper)) {
            return "PROCEDURE";
        }
        if ("SCALAR_FUNCTION".equals(upper) || "TABLE_VALUED_FUNCTION".equals(upper)
                || "SQL_SCALAR_FUNCTION".equals(upper) || "SQL_TABLE_VALUED_FUNCTION".equals(upper)) {
            return "FUNCTION";
        }
        return upper;
    }

    private List<RoutineMetadata> extractExpectedRoutinesFromRubric(String gradingRubricJson) {
        if (gradingRubricJson == null || gradingRubricJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode rubric = objectMapper.readTree(gradingRubricJson);
            JsonNode routines = rubric.path("grading_payload").path("routines");
            if (!routines.isArray() || routines.isEmpty()) {
                return List.of();
            }
            List<RoutineMetadata> result = new ArrayList<>();
            for (JsonNode r : routines) {
                String name = r.path("expected_name").asText("").trim();
                if (name.isBlank()) continue;
                String type = r.path("expected_type").asText("").trim();
                String returnType = r.path("expected_return_type").asText("").trim();

                List<RoutineMetadata.ParameterMetadata> params = new ArrayList<>();
                JsonNode pNode = r.path("parameters");
                if (pNode.isArray()) {
                    for (JsonNode p : pNode) {
                        params.add(RoutineMetadata.ParameterMetadata.builder()
                                .parameterName(p.path("name").asText("").trim())
                                .dataType(p.path("expected_type").asText("").trim())
                                .parameterMode(p.path("expected_mode").asText("IN").trim())
                                .build());
                    }
                }

                result.add(RoutineMetadata.builder()
                        .routineName(name)
                        .routineType(type)
                        .dataType(returnType.isBlank() ? null : returnType)
                        .parameters(params)
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.warn("Không thể phân tích routines[] từ rubric: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Logs (info-level, no penalty) routines present in the teacher schema but
     * not listed as required in the rubric. These are helper FN/SP that the
     * teacher used as implementation detail — students aren't required to
     * recreate them.
     */
    private void logHelperRoutinesIgnored(Long questionId,
                                          List<RoutineMetadata> rubricRoutines,
                                          List<RoutineMetadata> teacherRoutines) {
        if (teacherRoutines == null || teacherRoutines.isEmpty()) return;
        Set<String> required = new HashSet<>();
        for (RoutineMetadata r : rubricRoutines) {
            if (r.getRoutineName() != null) required.add(r.getRoutineName().toLowerCase());
        }
        for (RoutineMetadata t : teacherRoutines) {
            if (t.getRoutineName() == null) continue;
            if (!required.contains(t.getRoutineName().toLowerCase())) {
                log.info("[Câu {}] Đáp án giáo viên dùng routine phụ trợ {} {} không có trong rubric routines[] — không bắt buộc sinh viên tạo",
                        questionId, t.getRoutineType(), t.getRoutineName());
            }
        }
    }

    private String extractSetupScriptFromRubric(String gradingRubricJson) {
        if (gradingRubricJson == null || gradingRubricJson.isBlank()) {
            return "";
        }
        try {
            JsonNode rubric = objectMapper.readTree(gradingRubricJson);
            JsonNode testCases = rubric.path("grading_payload").path("test_cases");
            if (testCases.isArray() && testCases.size() > 0) {
                StringBuilder setupBuilder = new StringBuilder();
                for (JsonNode tc : testCases) {
                    String setupScript = tc.path("setup_script").asText("");
                    if (!setupScript.isBlank()) {
                        setupScript = setupScript.replace("\\n", "\n").replace("\\t", "\t");
                        setupBuilder.append(setupScript).append("\n");
                    }
                }
                return setupBuilder.toString();
            }
        } catch (Exception e) {
            return "";
        }
        return "";
    }
}
