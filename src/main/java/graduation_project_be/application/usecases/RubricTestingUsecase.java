package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.GeminiService;
import graduation_project_be.application.usecases.request.GenerateGradingRubricRequest;
import graduation_project_be.application.usecases.request.ExecuteSelectQueryRequest;
import graduation_project_be.application.usecases.request.TestGradeCreateTableRequest;
import graduation_project_be.application.usecases.request.TestGradeInsertRequest;
import graduation_project_be.application.usecases.request.TestGradeSelectRequest;
import graduation_project_be.application.usecases.response.BuildInsertTablesResponse;
import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import graduation_project_be.application.usecases.response.ExecuteSelectTestCaseResponse;
import graduation_project_be.application.usecases.response.RubricTestGradeResponse;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.TableMetadata;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class RubricTestingUsecase {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern INSERT_TABLE_ISSUE_PATTERN = Pattern.compile(
            "Bang\\s+([^:]+):\\s*thieu\\s+(\\d+)\\s+dong,\\s*sai\\s+(\\d+)\\s+o,\\s*du\\s+(\\d+)\\s+dong,\\s*sai\\s+thu\\s+tu\\s+(\\d+)\\s+dong(?:,\\s*tru\\s+([0-9]+(?:\\.[0-9]+)?)\\s*diem)?\\.",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_INTO_PATTERN = Pattern.compile(
            "(?i)\\bINSERT\\s+INTO\\s+((?:\\[[^\\]]+\\]|[A-Za-z0-9_]+)(?:\\s*\\.\\s*(?:\\[[^\\]]+\\]|[A-Za-z0-9_]+)){0,2})");

    private final GeminiService geminiService;
    private final ExamSchemaService examSchemaService;
    private final GetExamQuestionsUsecase getExamQuestionsUsecase;
    private final GradeExamUsecase gradeExamUsecase;
    private final ObjectMapper objectMapper;

    public String generateGradingRubric(GenerateGradingRubricRequest request) {
        String priorQuestionContext = "";
        try {
            List<GenerateGradingRubricRequest.ContextQuery> contextQueries = request.contextQueries();
            if (contextQueries != null && !contextQueries.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < contextQueries.size(); i++) {
                    GenerateGradingRubricRequest.ContextQuery item = contextQueries.get(i);
                    String itemQuery = item.correctQuery();
                    if (itemQuery == null || itemQuery.isBlank()) {
                        continue;
                    }
                    String itemType = item.questionType() == null ? "" : item.questionType();
                    String itemContent = item.content() == null ? "" : item.content();
                    sb.append("[QUESTION ").append(i + 1).append("] type=")
                            .append(itemType.isBlank() ? "UNKNOWN" : itemType)
                            .append("\n")
                            .append("content=")
                            .append(itemContent)
                            .append("\n")
                            .append("correctQuery=\n")
                            .append(itemQuery)
                            .append("\n\n");
                }
                priorQuestionContext = sb.toString();
            }
        } catch (Exception ignored) {
            priorQuestionContext = "";
        }

        return geminiService.generateGradingRubric(
                request.correctQuery(),
                request.questionContent(),
                request.totalPoints(),
                request.questionType(),
                priorQuestionContext);
    }

    public RubricTestGradeResponse testGradeInsert(TestGradeInsertRequest request) {
        String correctQuery = request.correctQuery();
        String studentQuery = request.studentQuery();
        String gradingRubric = request.gradingRubric();
        double totalPoints = request.totalPoints();

        ExamQuestion fakeQuestion = new ExamQuestion();
        fakeQuestion.setQuestionType(QuestionType.INSERT_DATA);
        fakeQuestion.setPoints(BigDecimal.valueOf(totalPoints));
        fakeQuestion.setGradingRubric(gradingRubric);
        fakeQuestion.setCorrectQuery(correctQuery);

        String suffix = String.valueOf(System.currentTimeMillis());
        String teacherSchema = "test_grade_teacher_" + suffix;
        String studentSchema = "test_grade_student_" + suffix;

        try {
            JsonNode rubric = objectMapper.readTree(gradingRubric);
            JsonNode payload = resolveInsertPayload(rubric);
            JsonNode settings = payload.path("grading_settings");
            String seedSchemaScript = payload.path("seed_schema_script").asText(rubric.path("seed_schema_script").asText(""));
            String dependsOnQuestionIdRaw = settings.path("depends_on_question_id").asText("").trim();
            boolean useConstraintWorkaround = settings.path("allow_cyclic_fk_workaround").asBoolean(true);
            List<Map<String, Object>> details = new ArrayList<>();
            List<ExamQuestionResponse> examQuestions = getExamQuestionsUsecase.execute(request.examId());

            examSchemaService.resetSchema(teacherSchema);
            examSchemaService.resetSchema(studentSchema);

            String currentCorrectNormalized = normalizeSqlForExecution(correctQuery);
            int teacherPrepared = executeExistingAnswersForSchema(
                    examQuestions,
                    teacherSchema,
                    details,
                    null,
                    false,
                    currentCorrectNormalized);
            int studentPrepared = executeExistingAnswersForSchema(
                    examQuestions,
                    studentSchema,
                    details,
                    null,
                    false,
                    currentCorrectNormalized);
            if (teacherPrepared > 0 || studentPrepared > 0) {
                details.add(Map.of(
                        "type", "info",
                        "message", "Đã chạy đáp án các câu hiện có trước khi chấm thử (teacher="
                                + teacherPrepared + ", student=" + studentPrepared + ")",
                        "points", 0));
            }

            if (!seedSchemaScript.isBlank() && settings.path("inject_seed_schema").asBoolean(false)) {
                examSchemaService.executeSql(teacherSchema, seedSchemaScript);
                examSchemaService.executeSql(studentSchema, seedSchemaScript);
            }

            if (!dependsOnQuestionIdRaw.isBlank()) {
                try {
                    Long dependsOnQuestionId = Long.valueOf(dependsOnQuestionIdRaw);
                    ExamQuestionResponse dependentQuestion = examQuestions
                            .stream()
                            .filter(q -> q.id() != null && q.id().equals(dependsOnQuestionId))
                            .findFirst()
                            .orElse(null);

                    if (dependentQuestion == null) {
                        details.add(Map.of(
                                "type", "warning",
                                "message", "Không tìm thấy câu phụ thuộc ID=" + dependsOnQuestionId,
                                "points", 0));
                    } else if (dependentQuestion.correctQuery() == null
                            || dependentQuestion.correctQuery().isBlank()) {
                        details.add(Map.of(
                                "type", "warning",
                                "message", "Câu phụ thuộc #" + dependsOnQuestionId
                                        + " không có correctQuery để khởi tạo dữ liệu",
                                "points", 0));
                    } else {
                        details.add(Map.of(
                                "type", "info",
                                "message", "Đã đảm bảo câu phụ thuộc #" + dependsOnQuestionId
                                        + " nằm trong bước tiền xử lý đáp án",
                                "points", 0));
                    }
                } catch (NumberFormatException nfe) {
                    details.add(Map.of(
                            "type", "warning",
                            "message", "depends_on_question_id không hợp lệ: " + dependsOnQuestionIdRaw,
                            "points", 0));
                } catch (Exception depEx) {
                    details.add(Map.of(
                            "type", "warning",
                            "message", "Không thể xác nhận câu phụ thuộc: " + depEx.getMessage(),
                            "points", 0));
                }
            }

            if (useConstraintWorkaround) {
                setAllConstraintsEnabled(teacherSchema, false);
                setAllConstraintsEnabled(studentSchema, false);
                details.add(Map.of(
                        "type", "info",
                        "message", "Đang bật chế độ workaround FK vòng (NOCHECK CONSTRAINT)",
                        "points", 0));
            }

            try {
                examSchemaService.executeSql(teacherSchema, correctQuery);
            } catch (Exception e) {
                details.add(Map.of(
                        "type", "warning",
                        "message", "Không thể chạy correctQuery trên schema teacher: " + e.getMessage(),
                        "points", 0));
            }

            String compileError = null;
            try {
                examSchemaService.executeSql(studentSchema, studentQuery);
            } catch (Exception e) {
                compileError = e.getMessage();
            }

            if (useConstraintWorkaround) {
                try {
                    setAllConstraintsEnabled(teacherSchema, true);
                } catch (Exception e) {
                    details.add(Map.of(
                            "type", "warning",
                            "message", "Dữ liệu đáp án teacher vi phạm ràng buộc sau khi kiểm tra lại: "
                                    + e.getMessage(),
                            "points", 0));
                }

                try {
                    setAllConstraintsEnabled(studentSchema, true);
                } catch (Exception e) {
                    String constraintError = "Vi phạm ràng buộc sau khi kiểm tra lại dữ liệu: " + e.getMessage();
                    if (compileError == null || compileError.isBlank()) {
                        compileError = constraintError;
                    }
                }
            }

            ExamSubmission fakeSubmission = new ExamSubmission();
            if (compileError != null) {
                String normalizedCompileError = compileError;
                if (compileError.contains("FOREIGN KEY constraint")) {
                    normalizedCompileError = compileError
                            + " | Gợi ý: Bài làm đang vi phạm thứ tự insert do phụ thuộc khóa ngoại (có thể là phụ thuộc vòng)."
                            + " Hãy điều chỉnh thứ tự insert hoặc tách bước tạo/liên kết dữ liệu cho phù hợp.";
                }

                fakeSubmission.setErrorMessage("Lỗi thực thi SQL: " + compileError);
                details.add(Map.of(
                        "type", "error",
                        "message", "Lỗi thực thi SQL: " + normalizedCompileError,
                        "points", 0));
                fakeSubmission.setScoreEarned(BigDecimal.ZERO);
            } else {
                gradeExamUsecase.gradeInsertDataByRubric(studentSchema, fakeQuestion, fakeSubmission);
            }

            double earnedPoints = fakeSubmission.getScoreEarned() != null
                    ? fakeSubmission.getScoreEarned().doubleValue()
                    : 0d;
            double totalDeduction = Math.max(0d, totalPoints - earnedPoints);

            boolean allPassed = fakeSubmission.getScoreEarned() != null
                    && fakeSubmission.getScoreEarned().compareTo(BigDecimal.valueOf(totalPoints)) >= 0;

            if (fakeSubmission.getErrorMessage() != null && !fakeSubmission.getErrorMessage().isBlank()) {
                appendInsertErrorDetails(details, fakeSubmission.getErrorMessage(), totalDeduction);
            } else if (allPassed && details.isEmpty()) {
                details.add(Map.of("type", "success", "message", "Tất cả dữ liệu đều chính xác", "points", totalPoints));
            }

                return RubricTestGradeResponse.of(
                    fakeSubmission.getScoreEarned() != null
                        ? fakeSubmission.getScoreEarned().doubleValue()
                        : 0,
                    totalPoints,
                    allPassed,
                    details);

        } catch (Exception e) {
            throw new RuntimeException("Lỗi chấm thử: " + e.getMessage(), e);
        } finally {
            try {
                examSchemaService.dropSchema(teacherSchema);
            } catch (Exception ignore) {
            }
            try {
                examSchemaService.dropSchema(studentSchema);
            } catch (Exception ignore) {
            }
        }
    }

    public ExecuteSelectTestCaseResponse executeSelectTestCase(ExecuteSelectQueryRequest request) {
        String correctQuery = request.correctQuery();
        String setupDependencyId = request.setupDependencyId();
        String setupCustomScript = request.setupCustomScript();

        if (correctQuery == null || correctQuery.isBlank()) {
            throw new IllegalArgumentException("Script đáp án giáo viên (correctQuery) không được để trống.");
        }

        List<ExamQuestionResponse> examQuestions = getExamQuestionsUsecase.execute(request.examId());

        String caseSchema = "test_grade_run_tc_" + System.currentTimeMillis();
        try {
            examSchemaService.resetSchema(caseSchema);

            List<Map<String, Object>> details = new ArrayList<>();
            int bootstrappedTables = executeExistingAnswersForSchema(
                    examQuestions,
                    caseSchema,
                    details,
                    "RUN_TC",
                    true);

            if (setupDependencyId != null && !setupDependencyId.isBlank()) {
                if (setupDependencyId.matches("\\d+")) {
                    try {
                        Long depId = Long.valueOf(setupDependencyId);
                        ExamQuestionResponse depQuestion = examQuestions.stream()
                                .filter(q -> q.id() != null && q.id().equals(depId))
                                .findFirst()
                                .orElse(null);
                        if (depQuestion != null && depQuestion.correctQuery() != null
                                && !depQuestion.correctQuery().isBlank()) {
                            examSchemaService.executeSql(
                                    caseSchema,
                                    normalizeSqlForExecution(depQuestion.correctQuery()));
                        }
                    } catch (Exception ignore) {
                    }
                }
            }

            if (setupCustomScript != null && !setupCustomScript.isBlank()) {
                if (containsForbiddenSchemaDdl(setupCustomScript)) {
                    throw new IllegalArgumentException(
                            "setup_custom_script không được chứa CREATE/DROP TABLE hoặc ALTER TABLE ngoài CHECK/NOCHECK CONSTRAINT.");
                }
                clearAllDataInSchema(caseSchema);
                executeSetupScriptWithFkFallback(caseSchema, setupCustomScript, "RUN_TC", details);
            }

            List<Map<String, Object>> teacherRows = examSchemaService.executeSql(caseSchema, correctQuery).getResultSet();

                List<ExecuteSelectTestCaseResponse.ColumnConfig> columnsConfig = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();

            if (teacherRows != null && !teacherRows.isEmpty()) {
                Map<String, Object> firstRow = teacherRows.get(0);
                for (String colName : firstRow.keySet()) {
                    columnsConfig.add(new ExecuteSelectTestCaseResponse.ColumnConfig(colName, "NVARCHAR"));
                }

                for (Map<String, Object> rowMap : teacherRows) {
                    List<String> rowList = new ArrayList<>();
                    for (String colName : firstRow.keySet()) {
                        Object val = rowMap.get(colName);
                        rowList.add(val == null ? "" : String.valueOf(val));
                    }
                    rows.add(rowList);
                }
            }

            return new ExecuteSelectTestCaseResponse(columnsConfig, rows);
        } finally {
            try {
                examSchemaService.dropSchema(caseSchema);
            } catch (Exception ignore) {
            }
        }
    }

    public BuildInsertTablesResponse buildInsertTablesFromAnswer(Long examId, String correctQuery) {
        if (correctQuery == null || correctQuery.isBlank()) {
            throw new IllegalArgumentException("Script đáp án giáo viên (correctQuery) không được để trống.");
        }

        List<ExamQuestionResponse> examQuestions = getExamQuestionsUsecase.execute(examId);

        String schemaName = "test_build_insert_" + System.currentTimeMillis();
        String safeSchema = safeIdentifier(schemaName, "schemaName");

        try {
            examSchemaService.resetSchema(schemaName);

            String normalizedCorrectSql = normalizeSqlForExecution(correctQuery);
            if (normalizedCorrectSql.isBlank()) {
                throw new IllegalArgumentException("SQL đáp án không hợp lệ sau khi chuẩn hóa.");
            }

            List<Map<String, Object>> details = new ArrayList<>();
            int preparedCount = executeExistingAnswersForSchema(
                    examQuestions,
                    schemaName,
                    details,
                    "BUILD_INSERT",
                    false,
                    normalizedCorrectSql);

            examSchemaService.executeSql(schemaName, normalizedCorrectSql);

            Set<String> targetTables = extractInsertedTableNames(normalizedCorrectSql);
            if (targetTables.isEmpty()) {
                targetTables = extractInsertedTableNames(correctQuery);
            }

            if (targetTables.isEmpty()) {
                throw new IllegalArgumentException(
                        "Không nhận diện được bảng INSERT từ SQL đáp án. Vui lòng kiểm tra cú pháp INSERT INTO.");
            }

            List<TableMetadata> metadataList = examSchemaService.extractMetadata(schemaName);
            Map<String, TableMetadata> metadataByName = new LinkedHashMap<>();
            for (TableMetadata tableMetadata : metadataList) {
                if (tableMetadata == null || tableMetadata.getTableName() == null) {
                    continue;
                }
                metadataByName.put(
                        tableMetadata.getTableName().toLowerCase(Locale.ROOT),
                        tableMetadata);
            }

            List<BuildInsertTablesResponse.InsertTableConfig> tables = new ArrayList<>();
            for (String tableName : targetTables) {
                String safeTableName;
                try {
                    safeTableName = safeIdentifier(tableName, "tableName");
                } catch (Exception ignored) {
                    continue;
                }

                TableMetadata tableMetadata = metadataByName.get(safeTableName.toLowerCase(Locale.ROOT));
                if (tableMetadata == null) {
                    continue;
                }

                List<Map<String, Object>> expectedData = examSchemaService.executeAdminSql(
                        "SELECT * FROM [" + safeSchema + "].[" + safeTableName + "]")
                        .getResultSet();

                List<BuildInsertTablesResponse.InsertColumnConfig> columnsConfig = new ArrayList<>();
                for (TableMetadata.ColumnMetadata column : tableMetadata.getColumns()) {
                    columnsConfig.add(new BuildInsertTablesResponse.InsertColumnConfig(
                            column.getColumnName(),
                            column.isPrimaryKey(),
                            true,
                            "EXACT"));
                }

                tables.add(new BuildInsertTablesResponse.InsertTableConfig(
                        tableMetadata.getTableName(),
                        "PARTIAL_BY_COLUMN",
                        columnsConfig,
                        expectedData == null ? List.of() : expectedData));
            }

            return new BuildInsertTablesResponse(
                    tables,
                    preparedCount,
                    targetTables.size());
        } finally {
            try {
                examSchemaService.dropSchema(schemaName);
            } catch (Exception ignore) {
            }
        }
    }

    public RubricTestGradeResponse testGradeSelect(TestGradeSelectRequest request) {
        String studentQuery = request.studentQuery();
        String correctQuery = request.correctQuery();
        String gradingRubric = request.gradingRubric();
        double totalPoints = request.totalPoints();

        try {
            JsonNode rubric = objectMapper.readTree(gradingRubric);
            JsonNode payload = rubric.path("grading_payload");
            JsonNode globalRules = payload.path("global_grading_rules");
            JsonNode selectRules = resolveSelectGradingRules(rubric, payload);
            boolean hasRuleBasedScoring = hasSelectGradingRules(selectRules);
            JsonNode testCases = payload.path("test_cases");

            boolean strictOrdering = readBoolean(globalRules.path("strict_ordering"), false);
            boolean allowPartialRowCredit = readBoolean(globalRules.path("allow_partial_row_credit"), true);
            double wrongColumnOrderPenalty = globalRules.path("wrong_column_order_penalty")
                    .asDouble(globalRules.path("wrong_column_name_penalty").asDouble(0.1));
            if (wrongColumnOrderPenalty < 0) {
                wrongColumnOrderPenalty = 0;
            }
            double extraRowPenalty = globalRules.path("extra_row_penalty").asDouble(0.0);
            double baselineWeightRatio = globalRules.path("baseline_weight_ratio").asDouble(0.0);
            if (baselineWeightRatio < 0) {
                baselineWeightRatio = 0;
            }
            if (baselineWeightRatio > 1) {
                baselineWeightRatio = 1;
            }
            BigDecimal baselineMaxPoints = BigDecimal.valueOf(totalPoints)
                    .multiply(BigDecimal.valueOf(baselineWeightRatio));

            List<ExamQuestionResponse> examQuestions = getExamQuestionsUsecase.execute(request.examId());
            List<Map<String, Object>> details = new ArrayList<>();
                AtomicBoolean wrongColumnOrderFlag = new AtomicBoolean(false);

            if (testCases.isMissingNode() || !testCases.isArray() || testCases.size() == 0) {
                if (hasRuleBasedScoring && !correctQuery.isBlank()) {
                    String baselineSchema = "test_grade_select_rule_base_" + System.currentTimeMillis();
                    try {
                        examSchemaService.resetSchema(baselineSchema);
                        int baselinePrepared = executeExistingAnswersForSchema(
                                examQuestions,
                                baselineSchema,
                                details,
                                "RULE_BASE");

                        List<Map<String, Object>> expectedBaseline = examSchemaService.executeSql(
                                baselineSchema,
                                correctQuery).getResultSet();
                        List<Map<String, Object>> actualBaseline = examSchemaService.executeSql(
                                baselineSchema,
                                studentQuery).getResultSet();

                        List<String> expectedColumns = new ArrayList<>();
                        List<List<String>> expectedRows = new ArrayList<>();
                        if (expectedBaseline != null && !expectedBaseline.isEmpty()) {
                            expectedColumns.addAll(expectedBaseline.get(0).keySet());
                            for (Map<String, Object> map : expectedBaseline) {
                                expectedRows.add(toRowValues(map, expectedColumns));
                            }
                        }

                        BigDecimal maxPoints = BigDecimal.valueOf(totalPoints);
                        BigDecimal earned = evaluateSelectCase(
                                "RULE_BASE",
                                "Dữ liệu từ đáp án các câu trước (đã chạy " + baselinePrepared + " đáp án)",
                                maxPoints,
                                expectedRows.size(),
                                expectedColumns,
                                expectedRows,
                                actualBaseline,
                                strictOrdering,
                                allowPartialRowCredit,
                                extraRowPenalty,
                                details,
                                wrongColumnOrderFlag,
                                selectRules,
                                true);

                        BigDecimal normalizedEarned = earned.setScale(2, RoundingMode.HALF_UP);
                        boolean allPassed = normalizedEarned.compareTo(maxPoints.setScale(2, RoundingMode.HALF_UP)) >= 0;

                        return RubricTestGradeResponse.of(
                                normalizedEarned.doubleValue(),
                                totalPoints,
                                allPassed,
                                details);
                    } catch (Exception baselineEx) {
                        return RubricTestGradeResponse.of(
                                0,
                                totalPoints,
                                false,
                                List.of(Map.of(
                                        "type", "error",
                                        "message", "Lỗi chấm SELECT theo grading_rules: " + baselineEx.getMessage(),
                                        "points", 0)));
                    } finally {
                        try {
                            examSchemaService.dropSchema(baselineSchema);
                        } catch (Exception ignore) {
                        }
                    }
                }

                return RubricTestGradeResponse.of(
                    0,
                    totalPoints,
                    false,
                    List.of(Map.of(
                        "type", "error",
                        "message", "Rubric SELECT không có test_cases",
                        "points", 0)));
            }

            BigDecimal earnedTotal = BigDecimal.ZERO;
            boolean allPassed = true;

            if (!correctQuery.isBlank()) {
                String baselineSchema = "test_grade_select_baseline_" + System.currentTimeMillis();
                try {
                    examSchemaService.resetSchema(baselineSchema);
                    int baselinePrepared = executeExistingAnswersForSchema(
                            examQuestions,
                            baselineSchema,
                            details,
                            "BASELINE");

                    List<Map<String, Object>> expectedBaseline = examSchemaService.executeSql(
                            baselineSchema,
                            correctQuery).getResultSet();
                    List<Map<String, Object>> actualBaseline = examSchemaService.executeSql(
                            baselineSchema,
                            studentQuery).getResultSet();

                    List<String> baselineExpectedColumns = new ArrayList<>();
                    List<List<String>> baselineExpectedRows = new ArrayList<>();
                    if (expectedBaseline != null && !expectedBaseline.isEmpty()) {
                        baselineExpectedColumns.addAll(expectedBaseline.get(0).keySet());
                        for (Map<String, Object> map : expectedBaseline) {
                            baselineExpectedRows.add(toRowValues(map, baselineExpectedColumns));
                        }
                    }

                    BigDecimal baselineEarned = evaluateSelectCase(
                            "BASELINE",
                            "Dữ liệu từ đáp án các câu trước (đã chạy " + baselinePrepared + " đáp án)",
                            baselineMaxPoints,
                            baselineExpectedRows.size(),
                            baselineExpectedColumns,
                            baselineExpectedRows,
                            actualBaseline,
                            strictOrdering,
                            allowPartialRowCredit,
                            extraRowPenalty,
                            details,
                            wrongColumnOrderFlag,
                            selectRules,
                            hasRuleBasedScoring);

                    earnedTotal = earnedTotal.add(baselineEarned);
                    if (baselineEarned.compareTo(baselineMaxPoints) < 0) {
                        allPassed = false;
                    }
                } catch (Exception baselineEx) {
                    if (baselineMaxPoints.compareTo(BigDecimal.ZERO) > 0) {
                        allPassed = false;
                    }
                    details.add(Map.of(
                            "type", "error",
                            "message", "[BASELINE] Lỗi đối chiếu dữ liệu nền: " + baselineEx.getMessage(),
                            "points", 0));
                } finally {
                    try {
                        examSchemaService.dropSchema(baselineSchema);
                    } catch (Exception ignore) {
                    }
                }
            }

            for (int i = 0; i < testCases.size(); i++) {
                JsonNode tc = testCases.get(i);
                String caseId = tc.path("case_id").asText("TC_" + (i + 1));
                String caseName = tc.path("case_name").asText(caseId);
                double weightRatio = tc.path("weight_ratio").asDouble(0.0);
                BigDecimal caseMaxPoints = BigDecimal.valueOf(totalPoints)
                        .multiply(BigDecimal.valueOf(weightRatio));

                String caseSchema = "test_grade_select_case_" + System.currentTimeMillis() + "_" + i;
                try {
                    examSchemaService.resetSchema(caseSchema);

                    int bootstrappedTables = executeExistingAnswersForSchema(
                            examQuestions,
                            caseSchema,
                            details,
                            caseId,
                            true);
                    if (bootstrappedTables > 0) {
                        details.add(Map.of(
                                "type", "info",
                                "message", "[" + caseId + "] Đã dựng schema nền từ "
                                        + bootstrappedTables + " đáp án CREATE_TABLE",
                                "points", 0));
                    }

                    String setupDependencyId = tc.path("setup_dependency_id").asText("").trim();
                    if (!setupDependencyId.isBlank()) {
                        if (!setupDependencyId.matches("\\d+")) {
                            details.add(Map.of(
                                    "type", "info",
                                    "message", "[" + caseId
                                            + "] Bỏ qua setup_dependency_id không phải ID số: " + setupDependencyId,
                                    "points", 0));
                        } else {
                            try {
                                Long depId = Long.valueOf(setupDependencyId);
                                ExamQuestionResponse depQuestion = examQuestions.stream()
                                        .filter(q -> q.id() != null && q.id().equals(depId))
                                        .findFirst()
                                        .orElse(null);
                                if (depQuestion != null && depQuestion.correctQuery() != null
                                        && !depQuestion.correctQuery().isBlank()) {
                                    examSchemaService.executeSql(
                                            caseSchema,
                                            normalizeSqlForExecution(depQuestion.correctQuery()));
                                }
                            } catch (Exception depEx) {
                                details.add(Map.of(
                                        "type", "warning",
                                        "message", "[" + caseId + "] Không thể chạy setup_dependency_id: "
                                                + depEx.getMessage(),
                                        "points", 0));
                            }
                        }
                    }

                    String setupCustomScript = tc.path("setup_custom_script").asText("");
                    if (!setupCustomScript.isBlank()) {
                        if (containsForbiddenSchemaDdl(setupCustomScript)) {
                            throw new IllegalArgumentException(
                                    "setup_custom_script không được chứa CREATE/DROP TABLE hoặc ALTER TABLE ngoài CHECK/NOCHECK CONSTRAINT. "
                                            + "Schema đã được dựng từ đáp án CREATE_TABLE; hãy chỉ setup dữ liệu (DELETE/INSERT/UPDATE), "
                                            + "nếu cần vòng FK thì chỉ dùng ALTER TABLE ... NOCHECK/CHECK CONSTRAINT.");
                        }
                        clearAllDataInSchema(caseSchema);
                        executeSetupScriptWithFkFallback(caseSchema, setupCustomScript, caseId, details);
                        details.add(Map.of(
                                "type", "info",
                                "message", "[" + caseId
                                        + "] Đã xóa dữ liệu cũ và nạp dữ liệu test case từ setup_custom_script",
                                "points", 0));
                    }

                    List<Map<String, Object>> actualRows = examSchemaService.executeSql(caseSchema, studentQuery).getResultSet();
                    int expectedRowCount;
                    List<String> expectedColumns = new ArrayList<>();
                    List<List<String>> expectedRows = new ArrayList<>();

                    if (!correctQuery.isBlank()) {
                        List<Map<String, Object>> teacherRows = examSchemaService.executeSql(caseSchema, correctQuery).getResultSet();
                        expectedRowCount = teacherRows.size();

                        if (!teacherRows.isEmpty()) {
                            expectedColumns.addAll(teacherRows.get(0).keySet());
                            for (Map<String, Object> row : teacherRows) {
                                expectedRows.add(toRowValues(row, expectedColumns));
                            }
                        }

                        details.add(Map.of(
                                "type", "info",
                                "message", "[" + caseId + "] Dùng kết quả đáp án giáo viên làm expected cho test case",
                                "points", 0));
                    } else {
                        JsonNode expectedResult = tc.path("expected_result");
                        expectedRowCount = expectedResult.path("expected_row_count").asInt(0);
                        JsonNode columnsConfig = expectedResult.path("columns_config");
                        JsonNode expectedRowsNode = expectedResult.path("rows");

                        for (int c = 0; c < columnsConfig.size(); c++) {
                            expectedColumns.add(columnsConfig.get(c).path("column_name").asText(""));
                        }

                        for (int r = 0; r < expectedRowsNode.size(); r++) {
                            JsonNode row = expectedRowsNode.get(r);
                            List<String> values = new ArrayList<>();
                            for (int c = 0; c < row.size(); c++) {
                                values.add(row.get(c).isNull() ? null : row.get(c).asText());
                            }
                            expectedRows.add(values);
                        }
                    }

                    BigDecimal caseEarned = evaluateSelectCase(
                            caseId,
                            caseName,
                            caseMaxPoints,
                            expectedRowCount,
                            expectedColumns,
                            expectedRows,
                            actualRows,
                            strictOrdering,
                            allowPartialRowCredit,
                            extraRowPenalty,
                            details,
                            wrongColumnOrderFlag,
                            selectRules,
                            hasRuleBasedScoring);

                    earnedTotal = earnedTotal.add(caseEarned);
                    if (caseEarned.compareTo(caseMaxPoints) < 0) {
                        allPassed = false;
                    }
                } catch (Exception caseEx) {
                    allPassed = false;
                    String errorMessage = caseEx.getMessage() != null ? caseEx.getMessage() : "Lỗi không xác định";
                    details.add(Map.of(
                            "type", "error",
                            "message", "[" + caseId + "] Lỗi chạy test case: " + errorMessage,
                            "points", 0));

                    if (errorMessage.contains("Invalid column name")) {
                        details.add(Map.of(
                                "type", "warning",
                                "message", "[" + caseId + "] setup_custom_script đang dùng cột không tồn tại trong schema nền. "
                                        + "Hãy sửa script để chỉ dùng cột đã khai báo ở các câu CREATE_TABLE trước đó "
                                        + "(không tự thêm cột mới).",
                                "points", 0));
                    }
                } finally {
                    try {
                        examSchemaService.dropSchema(caseSchema);
                    } catch (Exception ignore) {
                    }
                }
            }

            if (!hasRuleBasedScoring && wrongColumnOrderFlag.get() && wrongColumnOrderPenalty > 0) {
                BigDecimal penalty = BigDecimal.valueOf(wrongColumnOrderPenalty);
                earnedTotal = earnedTotal.subtract(penalty);
                details.add(Map.of(
                        "type", "warning",
                        "message", "[TRỪ ĐIỂM GLOBAL] Sai thứ tự cột kết quả (trừ 1 lần cho toàn bộ câu)",
                        "points", -penalty.setScale(2, RoundingMode.HALF_UP).doubleValue()));
            }

            earnedTotal = earnedTotal.setScale(2, RoundingMode.HALF_UP);
            BigDecimal max = BigDecimal.valueOf(totalPoints);
            if (earnedTotal.compareTo(max) > 0) {
                earnedTotal = max;
            }
            if (earnedTotal.compareTo(BigDecimal.ZERO) < 0) {
                earnedTotal = BigDecimal.ZERO;
            }

                return RubricTestGradeResponse.of(
                    earnedTotal.doubleValue(),
                    totalPoints,
                    allPassed,
                    details);

        } catch (Exception e) {
            throw new RuntimeException("Lỗi chấm thử SELECT: " + e.getMessage(), e);
        }
    }

    public RubricTestGradeResponse testGradeCreateTable(TestGradeCreateTableRequest request) {
        String correctQuery = request.correctQuery();
        String studentQuery = request.studentQuery();
        String gradingRubric = request.gradingRubric();
        double totalPoints = request.totalPoints();

        String suffix = String.valueOf(System.currentTimeMillis());
        String teacherSchema = "test_grade_teacher_" + suffix;
        String studentSchema = "test_grade_student_" + suffix;

        try {
            examSchemaService.resetSchema(teacherSchema);
            examSchemaService.executeSql(teacherSchema, correctQuery);

            examSchemaService.resetSchema(studentSchema);
            try {
                examSchemaService.executeSql(studentSchema, studentQuery);
            } catch (Exception e) {
                return RubricTestGradeResponse.of(
                    0,
                    totalPoints,
                    false,
                    List.of(
                        Map.of("type", "error", "message",
                            "Lỗi cú pháp SQL: " + e.getMessage(), "points", 0)));
            }

            return executeRubricGradingV2(
                    studentSchema, teacherSchema, gradingRubric, totalPoints);

        } catch (Exception e) {
            throw new RuntimeException("Lỗi chấm thử: " + e.getMessage(), e);
        } finally {
            try {
                examSchemaService.dropSchema(teacherSchema);
            } catch (Exception ignore) {
            }
            try {
                examSchemaService.dropSchema(studentSchema);
            } catch (Exception ignore) {
            }
        }
    }

    private void setAllConstraintsEnabled(String schemaName, boolean enabled) {
        String safeSchema = safeIdentifier(schemaName, "schemaName");
        List<Map<String, Object>> tables = examSchemaService.executeAdminSql(
                "SELECT t.name AS TABLE_NAME "
                        + "FROM sys.tables t "
                        + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
                        + "WHERE s.name = '" + safeSchema + "'").getResultSet();

        for (Map<String, Object> row : tables) {
            Object tableNameObj = row.get("TABLE_NAME");
            if (tableNameObj == null) {
                continue;
            }

            String tableName = safeIdentifier(String.valueOf(tableNameObj), "tableName");
            String sql = enabled
                    ? "ALTER TABLE [" + safeSchema + "].[" + tableName + "] WITH CHECK CHECK CONSTRAINT ALL"
                    : "ALTER TABLE [" + safeSchema + "].[" + tableName + "] NOCHECK CONSTRAINT ALL";
            examSchemaService.executeAdminSql(sql);
        }
    }

    private String normalizeSqlForExecution(String sql) {
        if (sql == null) {
            return "";
        }

        String normalized = sql
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\r\n", "\n")
                .replace("\r", "\n");

        normalized = normalized.replaceAll(
                "(?i)(CREATE\\s+TABLE|ALTER\\s+TABLE|INSERT\\s+INTO|UPDATE\\s+|DELETE\\s+FROM|MERGE\\s+INTO|DROP\\s+TABLE|TRUNCATE\\s+TABLE|WITH\\s+)",
                "\n$1");

        normalized = normalized.replaceAll("(?s)/\\*.*?\\*/", " ");
        normalized = normalized.replaceAll("--[^\\r\\n]*", " ");

        normalized = normalized.replaceAll("[\\t\\x0B\\f ]+", " ");
        normalized = normalized.replaceAll("\n+", "\n");

        return normalized.trim();
    }

    private int executeExistingAnswersForSchema(
            List<ExamQuestionResponse> examQuestions,
            String schemaName,
            List<Map<String, Object>> details,
            String caseId) {
        return executeExistingAnswersForSchema(examQuestions, schemaName, details, caseId, false);
    }

    private int executeExistingAnswersForSchema(
            List<ExamQuestionResponse> examQuestions,
            String schemaName,
            List<Map<String, Object>> details,
            String caseId,
            boolean createTableOnly) {
        return executeExistingAnswersForSchema(
                examQuestions,
                schemaName,
                details,
                caseId,
                createTableOnly,
                null);
    }

    private int executeExistingAnswersForSchema(
            List<ExamQuestionResponse> examQuestions,
            String schemaName,
            List<Map<String, Object>> details,
            String caseId,
            boolean createTableOnly,
            String excludeNormalizedSql) {
        int preparedCount = 0;
        for (ExamQuestionResponse question : examQuestions) {
            if ("SELECT_QUERY".equalsIgnoreCase(question.questionType())) {
                continue;
            }
            if (createTableOnly && !"CREATE_TABLE".equalsIgnoreCase(question.questionType())) {
                continue;
            }
            if (question.correctQuery() == null || question.correctQuery().isBlank()) {
                continue;
            }

            String normalizedSql = normalizeSqlForExecution(question.correctQuery());
            if (normalizedSql.isBlank()) {
                continue;
            }
            if (excludeNormalizedSql != null
                    && !excludeNormalizedSql.isBlank()
                    && normalizedSql.equals(excludeNormalizedSql)) {
                continue;
            }

            try {
                examSchemaService.executeSql(schemaName, normalizedSql);
                preparedCount++;
            } catch (Exception ex) {
                String error = ex.getMessage() != null ? ex.getMessage() : "";
                boolean isDuplicateObject = error.contains("There is already an object named")
                        || error.contains("error code [2714]");
                String prefix = caseId == null || caseId.isBlank() ? "" : "[" + caseId + "] ";

                if (isDuplicateObject) {
                    details.add(Map.of(
                            "type", "info",
                            "message", prefix + "Bỏ qua câu #" + question.id()
                                    + " vì object đã tồn tại khi chuẩn bị dữ liệu",
                            "points", 0));
                } else {
                    details.add(Map.of(
                            "type", "warning",
                            "message", prefix + "Không thể chạy đáp án câu #" + question.id()
                                    + " trước chấm thử"
                                    + (createTableOnly ? " (pha dựng schema)" : "")
                                    + ": " + error,
                            "points", 0));
                }
            }
        }

        return preparedCount;
    }

    private boolean containsForbiddenSchemaDdl(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }

        String normalized = sql.toUpperCase();
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
            List<Map<String, Object>> details) {
        try {
            examSchemaService.executeSql(schemaName, setupScript);
        } catch (Exception ex) {
            String message = ex.getMessage() != null ? ex.getMessage() : "";
            boolean isFkConflict = message.contains("FOREIGN KEY constraint");
            if (!isFkConflict) {
                throw ex;
            }

            details.add(Map.of(
                    "type", "warning",
                    "message", "[" + caseId
                            + "] setup_custom_script gặp lỗi FK, hệ thống tự thử lại với NOCHECK CONSTRAINT",
                    "points", 0));

            setAllConstraintsEnabled(schemaName, false);
            try {
                examSchemaService.executeSql(schemaName, setupScript);
            } finally {
                setAllConstraintsEnabled(schemaName, true);
            }
        }
    }

    private void clearAllDataInSchema(String schemaName) {
        String safeSchema = safeIdentifier(schemaName, "schemaName");
        setAllConstraintsEnabled(safeSchema, false);
        try {
            List<Map<String, Object>> tables = examSchemaService.executeAdminSql(
                    "SELECT t.name AS TABLE_NAME "
                            + "FROM sys.tables t "
                            + "INNER JOIN sys.schemas s ON t.schema_id = s.schema_id "
                            + "WHERE s.name = '" + safeSchema + "'").getResultSet();

            for (Map<String, Object> row : tables) {
                Object tableNameObj = row.get("TABLE_NAME");
                if (tableNameObj == null) {
                    continue;
                }
                String tableName = safeIdentifier(String.valueOf(tableNameObj), "tableName");
                examSchemaService.executeAdminSql(
                        "DELETE FROM [" + safeSchema + "].[" + tableName + "]");
            }
        } finally {
            setAllConstraintsEnabled(safeSchema, true);
        }
    }

    private BigDecimal evaluateSelectCase(
            String caseId,
            String caseName,
            BigDecimal caseMaxPoints,
            int expectedRowCount,
            List<String> expectedColumns,
            List<List<String>> expectedRows,
            List<Map<String, Object>> actualRows,
            boolean strictOrdering,
            boolean allowPartialRowCredit,
            double extraRowPenalty,
            List<Map<String, Object>> details,
            AtomicBoolean wrongColumnOrderFlag,
            JsonNode selectRules,
            boolean hasRuleBasedScoring) {
        if (hasRuleBasedScoring) {
            return evaluateSelectCaseByRules(
                    caseId,
                    caseName,
                    caseMaxPoints,
                    expectedColumns,
                    expectedRows,
                    actualRows,
                    strictOrdering,
                    selectRules,
                    details);
        }

        if (expectedRows == null) {
            expectedRows = List.of();
        }

        int expectedRowsSize = expectedRows.size();
        int actualRowsSize = actualRows == null ? 0 : actualRows.size();

        if (expectedRowCount > 0 && actualRowsSize == 0) {
            details.add(Map.of(
                    "type", "error",
                    "message", "[" + caseId + "] " + caseName + ": không có dòng kết quả nào",
                    "points", 0));
            return BigDecimal.ZERO;
        }

        List<String> actualColumns = new ArrayList<>();
        if (actualRows != null && !actualRows.isEmpty()) {
            actualColumns.addAll(actualRows.get(0).keySet());
        }

        boolean wrongColumnOrder = !expectedColumns.isEmpty() && !sameColumnOrder(expectedColumns, actualColumns);
        if (wrongColumnOrder) {
            wrongColumnOrderFlag.set(true);
        }

        BigDecimal rowScore = expectedRowsSize > 0
                ? caseMaxPoints.divide(BigDecimal.valueOf(expectedRowsSize), 6, RoundingMode.HALF_UP)
                : caseMaxPoints;

        BigDecimal rowEarned = BigDecimal.ZERO;
        if (strictOrdering) {
            int limit = Math.min(expectedRowsSize, actualRowsSize);
            for (int i = 0; i < limit; i++) {
                List<String> actual = toRowValues(
                        actualRows.get(i),
                        expectedColumns.isEmpty() ? actualColumns : expectedColumns);
                List<String> expected = expectedRows.get(i);
                rowEarned = rowEarned.add(scoreRow(actual, expected, rowScore, allowPartialRowCredit));
            }
        } else {
            List<List<String>> remaining = new ArrayList<>();
            for (Map<String, Object> map : actualRows) {
                remaining.add(toRowValues(
                        map,
                        expectedColumns.isEmpty() ? actualColumns : expectedColumns));
            }

            for (List<String> expected : expectedRows) {
                int idx = findBestRowMatchIndex(remaining, expected);
                if (idx >= 0) {
                    List<String> actual = remaining.remove(idx);
                    rowEarned = rowEarned.add(scoreRow(actual, expected, rowScore, allowPartialRowCredit));
                }
            }
        }

        BigDecimal earned = rowEarned.min(caseMaxPoints);

        if (actualRowsSize > expectedRowsSize && extraRowPenalty > 0) {
            int extra = actualRowsSize - expectedRowsSize;
            BigDecimal penalty = caseMaxPoints.multiply(BigDecimal.valueOf(extraRowPenalty))
                    .multiply(BigDecimal.valueOf(extra));
            earned = earned.subtract(penalty);
        }

        if (earned.compareTo(BigDecimal.ZERO) < 0) {
            earned = BigDecimal.ZERO;
        }
        if (earned.compareTo(caseMaxPoints) > 0) {
            earned = caseMaxPoints;
        }

        details.add(Map.of(
                "type", earned.compareTo(caseMaxPoints) == 0 ? "success" : "warning",
                "message", "[" + caseId + "] " + caseName + ": " + earned.setScale(2, RoundingMode.HALF_UP)
                        + "/" + caseMaxPoints.setScale(2, RoundingMode.HALF_UP) + " điểm",
                "points", earned.setScale(2, RoundingMode.HALF_UP).doubleValue()));

        return earned;
    }

    private BigDecimal evaluateSelectCaseByRules(
            String caseId,
            String caseName,
            BigDecimal caseMaxPoints,
            List<String> expectedColumns,
            List<List<String>> expectedRows,
            List<Map<String, Object>> actualRows,
            boolean strictOrdering,
            JsonNode selectRules,
            List<Map<String, Object>> details) {
        List<List<String>> safeExpectedRows = expectedRows == null ? List.of() : expectedRows;
        List<Map<String, Object>> safeActualRows = actualRows == null ? List.of() : actualRows;

        List<String> actualColumns = safeActualRows.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(safeActualRows.get(0).keySet());

        List<String> effectiveExpectedColumns = new ArrayList<>();
        if (expectedColumns != null) {
            for (String column : expectedColumns) {
                if (column != null && !column.isBlank()) {
                    effectiveExpectedColumns.add(column);
                }
            }
        }
        if (effectiveExpectedColumns.isEmpty() && !actualColumns.isEmpty()) {
            effectiveExpectedColumns = new ArrayList<>(actualColumns);
        }

        List<Map<String, Object>> expectedRowMaps = buildExpectedRowMaps(effectiveExpectedColumns, safeExpectedRows);
        List<String> comparisonColumns = !effectiveExpectedColumns.isEmpty()
                ? new ArrayList<>(effectiveExpectedColumns)
                : new ArrayList<>(actualColumns);
        if (comparisonColumns.isEmpty() && !expectedRowMaps.isEmpty()) {
            comparisonColumns.addAll(expectedRowMaps.get(0).keySet());
        }

        if (compareSelectResultStrict(safeActualRows, expectedRowMaps, strictOrdering, comparisonColumns)) {
            BigDecimal full = caseMaxPoints.setScale(2, RoundingMode.HALF_UP);
            details.add(Map.of(
                    "type", "success",
                    "message", "[" + caseId + "] " + caseName + ": " + full
                            + "/" + caseMaxPoints.setScale(2, RoundingMode.HALF_UP) + " điểm",
                    "points", full.doubleValue()));
            return caseMaxPoints;
        }

        int expectedRowsCount = expectedRowMaps.size();
        int actualRowsCount = safeActualRows.size();
        int missingRows = Math.max(0, expectedRowsCount - actualRowsCount);
        int extraRows = Math.max(0, actualRowsCount - expectedRowsCount);

        int missingColumns = countMissingColumnsIgnoreCase(effectiveExpectedColumns, actualColumns);
        int extraColumns = countExtraColumnsIgnoreCase(effectiveExpectedColumns, actualColumns);
        int columnOrderViolations = (!effectiveExpectedColumns.isEmpty() && !actualColumns.isEmpty()
                && !sameColumnOrder(effectiveExpectedColumns, actualColumns)) ? 1 : 0;

        JsonNode rowOrderRule = findSelectRule(selectRules, "ROW_ORDER", "OUT_OF_ORDER");
        int rowOrderViolations = 0;
        if (strictOrdering && rowOrderRule != null && !hasSelectModifier(rowOrderRule, "SORT_ASC")) {
            rowOrderViolations = countSelectRowOrderViolations(safeActualRows, expectedRowMaps, comparisonColumns);
            if (rowOrderViolations == 0) {
                rowOrderViolations = 1;
            }
        }

        JsonNode cellNotEqualRule = findSelectRule(selectRules, "CELL_VALUE", "NOT_EQUAL");
        JsonNode cellNullRule = findSelectRule(selectRules, "CELL_VALUE", "IS_NULL");
        JsonNode cellCompareModifiers = firstNonEmptyArray(
                extractSelectRuleModifiers(cellNotEqualRule),
                extractSelectRuleModifiers(cellNullRule));

        List<SelectRowPair> rowPairs = buildSelectRowPairs(
                safeActualRows,
                expectedRowMaps,
                comparisonColumns,
                strictOrdering,
                cellCompareModifiers);
        int wrongCells = countSelectCellMismatches(rowPairs, comparisonColumns, cellCompareModifiers);
        int nullViolations = countSelectNullViolations(rowPairs, comparisonColumns, cellCompareModifiers);

        double casePoints = caseMaxPoints.doubleValue();
        int expectedColumnsCount = Math.max(1, comparisonColumns.size());
        int expectedRowsForPenalty = Math.max(1, expectedRowsCount);
        double rowPenaltyDefault = casePoints / expectedRowsForPenalty;
        double columnPenaltyDefault = casePoints / expectedColumnsCount;
        double cellPenaltyDefault = rowPenaltyDefault / expectedColumnsCount;

        List<SelectRuleApplication> applications = List.of(
                applySelectRule(selectRules, "ROW", "IS_MISSING", missingRows,
                        caseMaxPoints, rowPenaltyDefault,
                        "thieu " + missingRows + " dong"),
                applySelectRule(selectRules, "ROW", "IS_EXTRA", extraRows,
                        caseMaxPoints, rowPenaltyDefault,
                        "du " + extraRows + " dong"),
                applySelectRule(selectRules, "CELL_VALUE", "NOT_EQUAL", wrongCells,
                        caseMaxPoints, cellPenaltyDefault,
                        "sai " + wrongCells + " o du lieu"),
                applySelectRule(selectRules, "CELL_VALUE", "IS_NULL", nullViolations,
                        caseMaxPoints, cellPenaltyDefault,
                        "co " + nullViolations + " o gia tri rong"),
                applySelectRule(selectRules, "ROW_ORDER", "OUT_OF_ORDER", rowOrderViolations,
                        caseMaxPoints, rowPenaltyDefault,
                        "sai thu tu " + rowOrderViolations + " dong"),
                applySelectRule(selectRules, "COLUMN_ORDER", "OUT_OF_ORDER", columnOrderViolations,
                        caseMaxPoints, columnPenaltyDefault,
                        "sai thu tu cot ket qua"),
                applySelectRule(selectRules, "COLUMN", "IS_MISSING", missingColumns,
                        caseMaxPoints, columnPenaltyDefault,
                        "thieu " + missingColumns + " cot"),
                applySelectRule(selectRules, "COLUMN", "IS_EXTRA", extraColumns,
                        caseMaxPoints, columnPenaltyDefault,
                        "du " + extraColumns + " cot"));

        double earned = casePoints;
        int matchedRuleCount = 0;
        boolean failAllTriggered = false;
        StringBuilder issueBuilder = new StringBuilder();

        for (SelectRuleApplication application : applications) {
            if (!application.violationPresent()) {
                continue;
            }

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
            earned = 0d;
            appendSelectIssue(issueBuilder,
                    "Khong co grading_rules phu hop de danh gia cac sai lech tren " + caseId + ".");
        }

        if (earned < 0d) {
            earned = 0d;
        }
        if (earned > casePoints) {
            earned = casePoints;
        }

        BigDecimal earnedPoints = BigDecimal.valueOf(earned).setScale(8, RoundingMode.HALF_UP);
        BigDecimal delta = caseMaxPoints.subtract(earnedPoints).abs();
        boolean allChecksPassed = !failAllTriggered && delta.compareTo(new BigDecimal("0.0001")) <= 0;

        BigDecimal roundedEarned = earnedPoints.setScale(2, RoundingMode.HALF_UP);
        String scoreMessage = "[" + caseId + "] " + caseName + ": " + roundedEarned
                + "/" + caseMaxPoints.setScale(2, RoundingMode.HALF_UP) + " điểm";
        details.add(Map.of(
                "type", allChecksPassed ? "success" : "warning",
                "message", scoreMessage,
                "points", roundedEarned.doubleValue()));

        if (!allChecksPassed) {
            String issueMessage = issueBuilder.length() == 0
                    ? "Kết quả SELECT không khớp rubric chấm điểm."
                    : issueBuilder.toString().trim();
            details.add(Map.of(
                    "type", "error",
                    "message", "[" + caseId + "] " + issueMessage,
                    "points", 0));
        }

        return earnedPoints;
    }

    private JsonNode resolveSelectGradingRules(JsonNode rubric, JsonNode payload) {
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

    private JsonNode findSelectRule(JsonNode gradingRules, String target, String condition) {
        if (gradingRules == null || !gradingRules.isArray()) {
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

    private JsonNode extractSelectRuleModifiers(JsonNode ruleNode) {
        if (ruleNode == null || !ruleNode.isObject()) {
            return objectMapper.createArrayNode();
        }

        JsonNode modifiers = ruleNode.path("modifiers");
        return modifiers.isArray() ? modifiers : objectMapper.createArrayNode();
    }

    private JsonNode firstNonEmptyArray(JsonNode primary, JsonNode fallback) {
        if (primary != null && primary.isArray() && primary.size() > 0) {
            return primary;
        }
        if (fallback != null && fallback.isArray() && fallback.size() > 0) {
            return fallback;
        }
        return objectMapper.createArrayNode();
    }

    private boolean hasSelectModifier(JsonNode ruleNode, String expectedModifier) {
        if (expectedModifier == null || expectedModifier.isBlank()) {
            return false;
        }

        JsonNode modifiers = extractSelectRuleModifiers(ruleNode);
        for (JsonNode modifierNode : modifiers) {
            if (expectedModifier.equalsIgnoreCase(modifierNode.asText(""))) {
                return true;
            }
        }

        return false;
    }

    private List<Map<String, Object>> buildExpectedRowMaps(
            List<String> expectedColumns,
            List<List<String>> expectedRows) {
        List<Map<String, Object>> rowMaps = new ArrayList<>();
        if (expectedRows == null || expectedRows.isEmpty()) {
            return rowMaps;
        }

        for (List<String> expectedRow : expectedRows) {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            int expectedSize = expectedRow == null ? 0 : expectedRow.size();

            for (int i = 0; i < expectedSize; i++) {
                String key;
                if (expectedColumns != null && i < expectedColumns.size()) {
                    key = expectedColumns.get(i);
                } else {
                    key = "col_" + i;
                }
                rowMap.put(key, expectedRow.get(i));
            }

            rowMaps.add(rowMap);
        }

        return rowMaps;
    }

    private boolean compareSelectResultStrict(
            List<Map<String, Object>> actualRows,
            List<Map<String, Object>> expectedRows,
            boolean requireStrictOrder,
            List<String> comparisonColumns) {
        if (actualRows == null || expectedRows == null) {
            return false;
        }
        if (actualRows.size() != expectedRows.size()) {
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

        if (!requireStrictOrder) {
            Collections.sort(actualSignatures);
            Collections.sort(expectedSignatures);
        }

        return actualSignatures.equals(expectedSignatures);
    }

    private int countMissingColumnsIgnoreCase(List<String> expectedColumns, List<String> actualColumns) {
        if (expectedColumns == null || expectedColumns.isEmpty()) {
            return 0;
        }

        Set<String> actualSet = new HashSet<>();
        if (actualColumns != null) {
            for (String column : actualColumns) {
                if (column != null) {
                    actualSet.add(column.toLowerCase(Locale.ROOT));
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

    private int countExtraColumnsIgnoreCase(List<String> expectedColumns, List<String> actualColumns) {
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
                signature.append(normalizeSelectValue(getRowValueIgnoreCase(row, column))).append("|||");
            }
            return signature.toString().toLowerCase(Locale.ROOT);
        }

        for (Object value : row.values()) {
            signature.append(normalizeSelectValue(value)).append("|||");
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
            if (valuesEqualBySelectRule(actualValue, expectedValue, cellModifiers)) {
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
                if (!valuesEqualBySelectRule(actualValue, expectedValue, cellModifiers)) {
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

                String normalizedActual = applySelectModifiers(normalizeSelectValue(actualValue), cellModifiers);
                String normalizedExpected = applySelectModifiers(normalizeSelectValue(expectedValue), cellModifiers);
                if (!isNullLikeValue(normalizedExpected) && isNullLikeValue(normalizedActual)) {
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
            BigDecimal caseMaxPoints,
            double defaultPenaltyPerViolation,
            String violationSummary) {
        if (violationCount <= 0) {
            return SelectRuleApplication.noViolation();
        }

        JsonNode ruleNode = findSelectRule(gradingRules, target, condition);
        if (ruleNode == null) {
            return SelectRuleApplication.unmatchedViolation();
        }

        SelectRuleDecision decision = resolveSelectRuleDecision(
                ruleNode,
                caseMaxPoints,
                defaultPenaltyPerViolation);

        String ruleLabel = target.toUpperCase(Locale.ROOT) + "/" + condition.toUpperCase(Locale.ROOT);
        if (decision.ignore()) {
            String message = "Rule " + ruleLabel + " bo qua vi pham (" + violationSummary + ").";
            return SelectRuleApplication.matchedViolation(false, BigDecimal.ZERO, message);
        }

        if (decision.failAll()) {
            String message = "Rule " + ruleLabel + " kich hoat FAIL_ALL (" + violationSummary + ").";
            return SelectRuleApplication.matchedViolation(true, BigDecimal.ZERO, message);
        }

        BigDecimal deduction = BigDecimal.valueOf(Math.max(0d, decision.penaltyPerViolation()))
                .multiply(BigDecimal.valueOf(violationCount));

        String message = buildSelectRuleMessage(ruleLabel, violationSummary, deduction, decision.action());
        return SelectRuleApplication.matchedViolation(false, deduction, message);
    }

    private SelectRuleDecision resolveSelectRuleDecision(
            JsonNode ruleNode,
            BigDecimal caseMaxPoints,
            double defaultPenaltyPerViolation) {
        String action = ruleNode != null ? ruleNode.path("action").asText("").trim() : "";
        if (action.isBlank()) {
            action = "DEDUCT_POINTS";
        }

        double penaltyValue = -1d;
        if (ruleNode != null) {
            JsonNode penaltyNode = ruleNode.path("penalty_value");
            if (penaltyNode.isNumber()) {
                penaltyValue = penaltyNode.asDouble();
            } else if (penaltyNode.isTextual()) {
                penaltyValue = parseDoubleSafe(penaltyNode.asText(""), -1d);
            }
        }

        String normalizedAction = action.toUpperCase(Locale.ROOT);
        double safeDefaultPenalty = Math.max(0d, defaultPenaltyPerViolation);

        switch (normalizedAction) {
            case "IGNORE":
                return new SelectRuleDecision(normalizedAction, 0d, true, false);
            case "FAIL_ALL":
                return new SelectRuleDecision(normalizedAction, 0d, false, true);
            case "FAIL_ITEM":
                return new SelectRuleDecision(normalizedAction, safeDefaultPenalty, false, false);
            case "DEDUCT_PERCENTAGE": {
                double penalty = penaltyValue >= 0d
                        ? Math.max(0d, caseMaxPoints.doubleValue() * penaltyValue / 100d)
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

    private String buildSelectRuleMessage(
            String ruleLabel,
            String violationSummary,
            BigDecimal deduction,
            String action) {
        String formattedDeduction = deduction
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
        return String.format(
                Locale.ROOT,
                "Rule %s (%s, action=%s): tru %s diem.",
                ruleLabel,
                violationSummary,
                action,
                formattedDeduction);
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

    private Object getRowValueIgnoreCase(Map<String, Object> row, String columnName) {
        if (row == null || columnName == null) {
            return null;
        }

        if (row.containsKey(columnName)) {
            return row.get(columnName);
        }

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private boolean valuesEqualBySelectRule(Object actualValue, Object expectedValue, JsonNode modifiers) {
        String actual = applySelectModifiers(normalizeSelectValue(actualValue), modifiers);
        String expected = applySelectModifiers(normalizeSelectValue(expectedValue), modifiers);

        if (isNullLikeValue(actual) && isNullLikeValue(expected)) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.equalsIgnoreCase(expected)) {
            return true;
        }

        try {
            return new BigDecimal(actual).compareTo(new BigDecimal(expected)) == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeSelectValue(Object value) {
        if (value == null) {
            return null;
        }

        String normalized = String.valueOf(value).trim();
        if (normalized.isEmpty()) {
            return "";
        }

        try {
            return new BigDecimal(normalized).stripTrailingZeros().toPlainString();
        } catch (Exception ignored) {
            return normalized;
        }
    }

    private String applySelectModifiers(String value, JsonNode modifiers) {
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
                    current = canonicalizeSelectNumber(current, -1);
                    break;
                case "ROUND_TO_INT":
                    current = canonicalizeSelectNumber(current, 0);
                    break;
                case "ROUND_2_DECIMALS":
                    current = canonicalizeSelectNumber(current, 2);
                    break;
                case "SORT_ASC":
                    current = sortSelectTokensAscending(current);
                    break;
                default:
                    break;
            }
        }

        return current;
    }

    private String canonicalizeSelectNumber(String value, int targetScale) {
        if (value == null) {
            return null;
        }

        try {
            BigDecimal decimal = new BigDecimal(value.trim());
            if (targetScale >= 0) {
                decimal = decimal.setScale(targetScale, RoundingMode.HALF_UP);
            }
            return decimal.stripTrailingZeros().toPlainString();
        } catch (Exception ignored) {
            return value;
        }
    }

    private String sortSelectTokensAscending(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.isBlank()) {
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
        for (String token : rawTokens) {
            if (token == null) {
                continue;
            }
            String normalizedToken = token.trim();
            if (!normalizedToken.isEmpty()) {
                tokens.add(normalizedToken);
            }
        }

        if (tokens.size() <= 1) {
            return trimmed;
        }

        tokens.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(commaSeparated ? "," : " ", tokens);
    }

    private boolean isNullLikeValue(String value) {
        if (value == null) {
            return true;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() || "NULL".equalsIgnoreCase(trimmed);
    }

    private record SelectRowPair(Map<String, Object> actualRow, Map<String, Object> expectedRow) {
    }

    private record SelectRuleDecision(String action, double penaltyPerViolation, boolean ignore, boolean failAll) {
    }

    private record SelectRuleApplication(
            boolean violationPresent,
            boolean ruleMatched,
            boolean failAllTriggered,
            BigDecimal deduction,
            String message) {
        static SelectRuleApplication noViolation() {
            return new SelectRuleApplication(false, false, false, BigDecimal.ZERO, null);
        }

        static SelectRuleApplication unmatchedViolation() {
            return new SelectRuleApplication(true, false, false, BigDecimal.ZERO, null);
        }

        static SelectRuleApplication matchedViolation(boolean failAllTriggered, BigDecimal deduction, String message) {
            return new SelectRuleApplication(true, true, failAllTriggered, deduction, message);
        }
    }

    private int findBestRowMatchIndex(List<List<String>> actualRows, List<String> expectedRow) {
        int bestIdx = -1;
        int bestScore = -1;
        for (int i = 0; i < actualRows.size(); i++) {
            List<String> actual = actualRows.get(i);
            int score = 0;
            int limit = Math.min(actual.size(), expectedRow.size());
            for (int c = 0; c < limit; c++) {
                if (valuesEqualFlexible(actual.get(c), expectedRow.get(c))) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private BigDecimal scoreRow(List<String> actual, List<String> expected, BigDecimal rowScore, boolean allowPartial) {
        int expectedCells = expected == null ? 0 : expected.size();
        if (expectedCells == 0) {
            return rowScore;
        }

        int matched = 0;
        int limit = Math.min(actual.size(), expected.size());
        for (int i = 0; i < limit; i++) {
            if (valuesEqualFlexible(actual.get(i), expected.get(i))) {
                matched++;
            }
        }

        if (!allowPartial) {
            return matched == expectedCells ? rowScore : BigDecimal.ZERO;
        }

        BigDecimal ratio = BigDecimal.valueOf(matched)
                .divide(BigDecimal.valueOf(expectedCells), 6, RoundingMode.HALF_UP);
        return rowScore.multiply(ratio);
    }

    private List<String> toRowValues(Map<String, Object> row, List<String> orderedColumns) {
        List<String> values = new ArrayList<>();
        if (orderedColumns == null || orderedColumns.isEmpty()) {
            for (Object val : row.values()) {
                values.add(val == null ? null : String.valueOf(val));
            }
            return values;
        }

        for (String col : orderedColumns) {
            Object val = row.get(col);
            if (val == null && col != null && !row.containsKey(col)) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(col)) {
                        val = entry.getValue();
                        break;
                    }
                }
            }
            values.add(val == null ? null : String.valueOf(val));
        }
        return values;
    }

    private boolean sameColumnOrder(List<String> expectedColumns, List<String> actualColumns) {
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

    private boolean valuesEqualFlexible(String actual, String expected) {
        if (Objects.equals(actual, expected)) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }

        String a = actual.trim();
        String e = expected.trim();
        if (a.equalsIgnoreCase(e)) {
            return true;
        }

        try {
            BigDecimal an = new BigDecimal(a);
            BigDecimal en = new BigDecimal(e);
            return an.compareTo(en) == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private RubricTestGradeResponse executeRubricGradingV2(
            String studentSchema,
            String teacherSchema,
            String gradingRubricJson,
            double totalPoints) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(gradingRubricJson);
        } catch (Exception e) {
                return RubricTestGradeResponse.of(
                    0,
                    totalPoints,
                    false,
                    List.of(Map.of("type", "error", "message", "Rubric JSON khong hop le", "points", 0)),
                    0d);
        }

        List<TableMetadata> actualTables = examSchemaService.extractMetadata(studentSchema);
        CreateTableRubricEvaluator.CreateTableRubricGradeResult result =
                CreateTableRubricEvaluator.evaluate(
                        rubric,
                        actualTables,
                        BigDecimal.valueOf(totalPoints));

        return RubricTestGradeResponse.of(
            result.earnedPoints().doubleValue(),
            totalPoints,
            result.allPassed(),
            result.details(),
            result.totalDeductions().doubleValue());
    }

    private boolean readBoolean(JsonNode node, boolean defaultValue) {
        if (node == null || node.isNull() || node.isMissingNode()) {
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

    private JsonNode resolveInsertPayload(JsonNode rubric) {
        if (rubric == null || rubric.isNull() || rubric.isMissingNode()) {
            return objectMapper.createObjectNode();
        }

        JsonNode payload = rubric.path("grading_payload");
        if (payload != null && payload.isObject()) {
            return payload;
        }

        return rubric;
    }

    private Set<String> extractInsertedTableNames(String sql) {
        Set<String> tableNames = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) {
            return tableNames;
        }

        Matcher matcher = INSERT_INTO_PATTERN.matcher(sql);
        while (matcher.find()) {
            String rawIdentifier = matcher.group(1);
            String tableName = extractLastIdentifier(rawIdentifier);
            if (tableName != null && !tableName.isBlank()) {
                tableNames.add(tableName);
            }
        }

        return tableNames;
    }

    private String extractLastIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }

        String normalized = identifier.replaceAll("\\s+", "").trim();
        if (normalized.isBlank()) {
            return "";
        }

        String[] segments = normalized.split("\\.");
        String last = segments[segments.length - 1].trim();

        if (last.startsWith("[") && last.endsWith("]") && last.length() > 1) {
            last = last.substring(1, last.length() - 1);
        }
        if (last.startsWith("\"") && last.endsWith("\"") && last.length() > 1) {
            last = last.substring(1, last.length() - 1);
        }

        return last.trim();
    }

    private void appendInsertErrorDetails(
            List<Map<String, Object>> details,
            String rawMessage,
            double fallbackTotalDeduction) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }

        Matcher matcher = INSERT_TABLE_ISSUE_PATTERN.matcher(rawMessage);
        List<Map<String, Object>> parsedIssues = new ArrayList<>();

        while (matcher.find()) {
            String tableName = matcher.group(1);
            int missingRows = parseIntegerSafe(matcher.group(2));
            int wrongCells = parseIntegerSafe(matcher.group(3));
            int extraRows = parseIntegerSafe(matcher.group(4));
            int outOfOrderRows = parseIntegerSafe(matcher.group(5));
            double deduction = parseDoubleSafe(matcher.group(6), 0d);

            String message = String.format(
                    Locale.ROOT,
                    "Bang %s: thieu %d dong, sai %d o, du %d dong, sai thu tu %d dong.",
                    tableName,
                    missingRows,
                    wrongCells,
                    extraRows,
                    outOfOrderRows);

            parsedIssues.add(Map.of(
                    "type", "error",
                    "message", message,
                    "points", -roundTo2(deduction)));
        }

        if (!parsedIssues.isEmpty()) {
            details.addAll(parsedIssues);

            String remaining = INSERT_TABLE_ISSUE_PATTERN.matcher(rawMessage).replaceAll("").trim();
            if (!remaining.isBlank()) {
                details.add(Map.of(
                        "type", "error",
                        "message", remaining,
                        "points", 0));
            }
            return;
        }

        details.add(Map.of(
                "type", "error",
                "message", rawMessage,
                "points", -roundTo2(Math.max(0d, fallbackTotalDeduction))));
    }

    private int parseIntegerSafe(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double parseDoubleSafe(String rawValue, double defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(rawValue.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double roundTo2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String safeIdentifier(String identifier, String fieldName) {
        if (identifier == null || identifier.isBlank() || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier for " + fieldName);
        }
        return identifier;
    }
}
