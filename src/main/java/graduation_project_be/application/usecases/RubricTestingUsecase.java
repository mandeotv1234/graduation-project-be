package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.GeminiService;
import graduation_project_be.application.usecases.request.GenerateGradingRubricRequest;
import graduation_project_be.application.usecases.request.TestGradeCreateTableRequest;
import graduation_project_be.application.usecases.request.TestGradeInsertRequest;
import graduation_project_be.application.usecases.request.TestGradeSelectRequest;
import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.TableMetadata;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class RubricTestingUsecase {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

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

    public Map<String, Object> testGradeInsert(TestGradeInsertRequest request) {
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
            JsonNode payload = rubric.path("grading_payload");
            JsonNode settings = payload.path("grading_settings");
            String seedSchemaScript = payload.path("seed_schema_script").asText("");
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

            boolean allPassed = fakeSubmission.getScoreEarned() != null
                    && fakeSubmission.getScoreEarned().compareTo(BigDecimal.valueOf(totalPoints)) >= 0;

            if (fakeSubmission.getErrorMessage() != null && !fakeSubmission.getErrorMessage().isBlank()) {
                details.add(Map.of("type", "error", "message", fakeSubmission.getErrorMessage(), "points", 0));
            } else if (allPassed && details.isEmpty()) {
                details.add(Map.of("type", "success", "message", "Tất cả dữ liệu đều chính xác", "points", totalPoints));
            }

            return Map.of(
                    "earnedPoints", fakeSubmission.getScoreEarned() != null
                            ? fakeSubmission.getScoreEarned().doubleValue()
                            : 0,
                    "totalPoints", totalPoints,
                    "allPassed", allPassed,
                    "details", details);

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

    public Map<String, Object> testGradeSelect(TestGradeSelectRequest request) {
        String studentQuery = request.studentQuery();
        String correctQuery = request.correctQuery();
        String gradingRubric = request.gradingRubric();
        double totalPoints = request.totalPoints();

        try {
            JsonNode rubric = objectMapper.readTree(gradingRubric);
            JsonNode payload = rubric.path("grading_payload");
            JsonNode globalRules = payload.path("global_grading_rules");
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

            if (testCases.isMissingNode() || !testCases.isArray() || testCases.size() == 0) {
                return Map.of(
                        "earnedPoints", 0,
                        "totalPoints", totalPoints,
                        "allPassed", false,
                        "details", List.of(Map.of(
                                "type", "error",
                                "message", "Rubric SELECT không có test_cases",
                                "points", 0)));
            }

            BigDecimal earnedTotal = BigDecimal.ZERO;
            boolean allPassed = true;
            AtomicBoolean wrongColumnOrderFlag = new AtomicBoolean(false);

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
                            wrongColumnOrderFlag);

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
                            wrongColumnOrderFlag);

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

            if (wrongColumnOrderFlag.get() && wrongColumnOrderPenalty > 0) {
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

            return Map.of(
                    "earnedPoints", earnedTotal.doubleValue(),
                    "totalPoints", totalPoints,
                    "allPassed", allPassed,
                    "details", details);

        } catch (Exception e) {
            throw new RuntimeException("Lỗi chấm thử SELECT: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> testGradeCreateTable(TestGradeCreateTableRequest request) {
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
                return Map.of(
                        "earnedPoints", 0,
                        "totalPoints", totalPoints,
                        "allPassed", false,
                        "details", List.of(
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
            AtomicBoolean wrongColumnOrderFlag) {
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

    private Map<String, Object> executeRubricGradingV2(
            String studentSchema,
            String teacherSchema,
            String gradingRubricJson,
            double totalPoints) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(gradingRubricJson);
        } catch (Exception e) {
            return Map.of(
                    "earnedPoints", 0,
                    "totalPoints", totalPoints,
                    "totalDeductions", 0,
                    "allPassed", false,
                    "details", List.of(
                            Map.of("type", "error", "message", "Rubric JSON khong hop le", "points", 0)));
        }

        List<TableMetadata> actualTables = examSchemaService.extractMetadata(studentSchema);
        CreateTableRubricEvaluator.CreateTableRubricGradeResult result =
                CreateTableRubricEvaluator.evaluate(
                        rubric,
                        actualTables,
                        BigDecimal.valueOf(totalPoints));

        return Map.of(
                "earnedPoints", result.earnedPoints().doubleValue(),
                "totalPoints", totalPoints,
                "totalDeductions", result.totalDeductions().doubleValue(),
                "allPassed", result.allPassed(),
                "details", result.details());
    }

    private Map<String, Object> executeRubricGrading(
            String studentSchema,
            String teacherSchema,
            String gradingRubricJson,
            double totalPoints) {
        JsonNode rubric;
        try {
            rubric = objectMapper.readTree(gradingRubricJson);
        } catch (Exception e) {
            return Map.of(
                    "earnedPoints", 0,
                    "totalPoints", totalPoints,
                    "allPassed", false,
                    "details", List.of(
                            Map.of("type", "error", "message", "Rubric JSON không hợp lệ", "points", 0)));
        }

        List<TableMetadata> actualTables = examSchemaService.extractMetadata(studentSchema);
        List<TableMetadata> expectedTables = examSchemaService.extractMetadata(teacherSchema);
        JsonNode payload = rubric.path("grading_payload");
        JsonNode settings = payload.path("grading_settings");
        boolean caseSensitive = settings.path("case_sensitive_names").asBoolean(false);
        boolean positiveOnlyScoring = settings.path("positive_only_scoring").asBoolean(false);
        boolean failAllMode = "FAIL_ALL".equalsIgnoreCase(
                settings.path("syntax_error_action").asText("PARTIAL"));

        List<Map<String, Object>> details = new ArrayList<>();
        double earned = 0;
        boolean allPassed = true;

        JsonNode tables = payload.path("tables");
        for (int i = 0; i < tables.size(); i++) {
            JsonNode rt = tables.get(i);
            String expectedName = rt.path("expected_name").asText("");
            double existPts = rt.path("existence_points").asDouble(0);

            TableMetadata actualTable = actualTables.stream()
                    .filter(t -> caseSensitive
                            ? t.getTableName().equals(expectedName)
                            : t.getTableName().equalsIgnoreCase(expectedName))
                    .findFirst().orElse(null);

            TableMetadata expectedTable = expectedTables.stream()
                    .filter(t -> caseSensitive
                            ? t.getTableName().equals(expectedName)
                            : t.getTableName().equalsIgnoreCase(expectedName))
                    .findFirst().orElse(null);

            if (actualTable == null) {
                allPassed = false;
                double lost = existPts;
                JsonNode cols = rt.path("columns");
                for (int j = 0; j < cols.size(); j++) {
                    lost += cols.get(j).path("points").asDouble(0);
                }
                JsonNode cons = rt.path("constraints");
                for (int j = 0; j < cons.size(); j++) {
                    lost += cons.get(j).path("points").asDouble(0);
                }
                details.add(Map.of("type", "error", "message",
                        String.format("Thiếu bảng %s", expectedName),
                        "points", positiveOnlyScoring ? 0 : -lost));
                continue;
            }

            earned += existPts;
            details.add(Map.of("type", "success", "message",
                    String.format("Bảng %s tồn tại", expectedName), "points", existPts));

            JsonNode rubricCols = rt.path("columns");
            for (int j = 0; j < rubricCols.size(); j++) {
                JsonNode rc = rubricCols.get(j);
                String colName = rc.path("name").asText("");
                String expectedType = rc.path("expected_type").asText("");
                double colPts = rc.path("points").asDouble(0);
                double typePenalty = rc.path("type_mismatch_penalty").asDouble(0);

                TableMetadata.ColumnMetadata actualCol = actualTable.getColumns().stream()
                        .filter(c -> caseSensitive
                                ? c.getColumnName().equals(colName)
                                : c.getColumnName().equalsIgnoreCase(colName))
                        .findFirst().orElse(null);

                if (actualCol == null) {
                    allPassed = false;
                    details.add(Map.of("type", "error", "message",
                            String.format("Bảng %s: thiếu cột %s", expectedName, colName),
                            "points", positiveOnlyScoring ? 0 : -colPts));
                } else {
                    boolean typeMatch = matchesSqlType(actualCol.getRawDataType(), expectedType);
                    if (typeMatch) {
                        earned += colPts;
                        details.add(Map.of("type", "success", "message",
                                String.format("Bảng %s: cột %s (%s) ✓", expectedName, colName, expectedType),
                                "points", colPts));
                    } else {
                        allPassed = false;
                        double awarded = positiveOnlyScoring
                                ? 0
                                : Math.max(0, colPts - typePenalty);
                        earned += awarded;
                        details.add(Map.of("type", "warning", "message",
                                String.format("Bảng %s: cột %s sai kiểu (Kỳ vọng: %s, Thực tế: %s)",
                                        expectedName, colName, expectedType, actualCol.getDataType()),
                                "points", positiveOnlyScoring ? 0 : -typePenalty));
                    }
                }
            }

            JsonNode rubricCons = rt.path("constraints");
            for (int j = 0; j < rubricCons.size(); j++) {
                JsonNode rc = rubricCons.get(j);
                String cType = rc.path("type").asText("");
                double cPts = rc.path("points").asDouble(0);
                double cPenalty = rc.path("missing_penalty").asDouble(0);
                boolean found;

                if (("PRIMARY_KEY".equals(cType) || "FOREIGN_KEY".equals(cType))
                        && expectedTable != null
                        && !isConstraintExpectedByTeacherSchema(expectedTable, rc, caseSensitive)) {
                    earned += cPts;
                    continue;
                }

                switch (cType) {
                    case "PRIMARY_KEY":
                        JsonNode pkCols = rc.path("columns");
                        found = true;
                        for (int k = 0; k < pkCols.size(); k++) {
                            String pk = pkCols.get(k).asText();
                            boolean matched = actualTable.getColumns().stream()
                                    .anyMatch(c -> (caseSensitive ? c.getColumnName().equals(pk)
                                            : c.getColumnName().equalsIgnoreCase(pk)) && c.isPrimaryKey());
                            if (!matched) {
                                found = false;
                                break;
                            }
                        }
                        break;
                    case "FOREIGN_KEY":
                        String refTbl = rc.path("references_table").asText("");
                        JsonNode fkCols = rc.path("columns");
                        found = true;
                        for (int k = 0; k < fkCols.size(); k++) {
                            String fk = fkCols.get(k).asText();
                            boolean matched = actualTable.getColumns().stream()
                                    .anyMatch(c -> (caseSensitive ? c.getColumnName().equals(fk)
                                            : c.getColumnName().equalsIgnoreCase(fk))
                                            && c.isForeignKey()
                                            && (caseSensitive ? refTbl.equals(c.getReferencesTable())
                                            : refTbl.equalsIgnoreCase(c.getReferencesTable())));
                            if (!matched) {
                                found = false;
                                break;
                            }
                        }
                        break;
                    default:
                        found = true;
                        break;
                }

                JsonNode conColumns = rc.path("columns");
                StringBuilder colList = new StringBuilder();
                for (int k = 0; k < conColumns.size(); k++) {
                    if (k > 0) {
                        colList.append(", ");
                    }
                    colList.append(conColumns.get(k).asText());
                }

                if (found) {
                    earned += cPts;
                    details.add(Map.of("type", "success", "message",
                            String.format("Bảng %s: ràng buộc %s [%s] ✓", expectedName, cType, colList),
                            "points", cPts));
                } else {
                    allPassed = false;
                    details.add(Map.of("type", "error", "message",
                            String.format("Bảng %s: thiếu ràng buộc %s [%s]", expectedName, cType, colList),
                            "points", positiveOnlyScoring ? 0 : -cPenalty));
                }
            }
        }

        earned = Math.round(earned * 100.0) / 100.0;
        if (failAllMode && !allPassed) {
            earned = 0;
            details.add(Map.of(
                    "type", "warning",
                    "message", "Rubric đang để FAIL_ALL: có lỗi nên câu này bị 0 điểm toàn bộ",
                    "points", 0));
        }
        if (earned > totalPoints) {
            earned = totalPoints;
        }
        if (earned < 0) {
            earned = 0;
        }

        return Map.of(
                "earnedPoints", earned,
                "totalPoints", totalPoints,
                "allPassed", allPassed,
                "details", details);
    }

    private boolean isConstraintExpectedByTeacherSchema(
            TableMetadata expectedTable,
            JsonNode constraintNode,
            boolean caseSensitive) {
        String type = constraintNode.path("type").asText("");
        JsonNode columns = constraintNode.path("columns");

        if (!columns.isArray() || columns.size() == 0) {
            return false;
        }

        if ("PRIMARY_KEY".equals(type)) {
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i).asText("");
                boolean matched = expectedTable.getColumns().stream()
                        .anyMatch(c -> (caseSensitive
                                ? c.getColumnName().equals(col)
                                : c.getColumnName().equalsIgnoreCase(col))
                                && c.isPrimaryKey());
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        if ("FOREIGN_KEY".equals(type)) {
            String refTbl = constraintNode.path("references_table").asText("");
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i).asText("");
                boolean matched = expectedTable.getColumns().stream()
                        .anyMatch(c -> (caseSensitive
                                ? c.getColumnName().equals(col)
                                : c.getColumnName().equalsIgnoreCase(col))
                                && c.isForeignKey()
                                && (refTbl == null || refTbl.isBlank()
                                || (caseSensitive
                                ? refTbl.equals(c.getReferencesTable())
                                : refTbl.equalsIgnoreCase(c.getReferencesTable()))));
                if (!matched) {
                    return false;
                }
            }
            return true;
        }

        return true;
    }

    private boolean matchesSqlType(String actualType, String expectedType) {
        return normalizeSqlType(actualType).equals(normalizeSqlType(expectedType));
    }

    private String normalizeSqlType(String sqlType) {
        if (sqlType == null) {
            return "";
        }
        String normalized = sqlType.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }

        int parenIndex = normalized.indexOf('(');
        if (parenIndex >= 0) {
            normalized = normalized.substring(0, parenIndex);
        }

        int spaceIndex = normalized.indexOf(' ');
        if (spaceIndex >= 0) {
            normalized = normalized.substring(0, spaceIndex);
        }

        return normalized.trim();
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

    private String safeIdentifier(String identifier, String fieldName) {
        if (identifier == null || identifier.isBlank() || !IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier for " + fieldName);
        }
        return identifier;
    }
}
