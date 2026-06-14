package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.usecases.grading.GradeDecision;
import graduation_project_be.application.usecases.grading.InsertDataQuestionGrader;
import graduation_project_be.application.usecases.grading.SelectQuestionGrader;
import graduation_project_be.application.usecases.grading.SelectTrapDiscriminationChecker;
import graduation_project_be.application.usecases.request.GenerateGradingRubricRequest;
import graduation_project_be.application.usecases.request.ExecuteSelectQueryRequest;
import graduation_project_be.application.usecases.request.TestGradeCreateTableRequest;
import graduation_project_be.application.usecases.request.TestGradeInsertRequest;
import graduation_project_be.application.usecases.request.TestGradeRoutineRequest;
import graduation_project_be.application.usecases.request.TestGradeTriggerRequest;
import graduation_project_be.application.usecases.request.TestGradeSelectRequest;
import graduation_project_be.application.usecases.response.BuildCreateTablesResponse;
import graduation_project_be.application.usecases.response.BuildInsertTablesResponse;
import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import graduation_project_be.application.usecases.response.ExecuteSelectTestCaseResponse;
import graduation_project_be.application.usecases.response.RubricTestGradeResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.SqlExecutionResult;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.TriggerMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class RubricTestingUsecase {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern INSERT_TABLE_ISSUE_PATTERN = Pattern.compile(
            "(?:Bang|Bảng)\\s+([^:]+):\\s*(?:thieu|thiếu)\\s+(\\d+)\\s+(?:dong|dòng),\\s*sai\\s+(\\d+)\\s+(?:o|ô),\\s*(?:du|dư)\\s+(\\d+)\\s+(?:dong|dòng),\\s*(?:sai\\s+thu\\s+tu|sai\\s+thứ\\s+tự)\\s+(\\d+)\\s+(?:dong|dòng)(?:,\\s*(?:tru|trừ)\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(?:diem|điểm))?\\.",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_INTO_PATTERN = Pattern.compile(
            "(?i)\\bINSERT\\s+INTO\\s+((?:\\[[^\\]]+\\]|[A-Za-z0-9_]+)(?:\\s*\\.\\s*(?:\\[[^\\]]+\\]|[A-Za-z0-9_]+)){0,2})");
    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?i)\\bCREATE\\s+TABLE\\s+((?:\\[[^\\]]+\\]|[A-Za-z0-9_]+)(?:\\s*\\.\\s*(?:\\[[^\\]]+\\]|[A-Za-z0-9_]+)){0,2})");

    private final AIService geminiService;
    private final ExamSchemaService examSchemaService;
    private final ExamRepository examRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final GetExamQuestionsUsecase getExamQuestionsUsecase;
    private final InsertDataQuestionGrader insertDataGrader;
    private final ObjectMapper objectMapper;
    // Reused so the SELECT preview falls back to dataset grading exactly like runtime does.
    private final SelectQuestionGrader selectGrader;
    // Stateless helper; constructed directly so it stays out of the generated constructor.
    private final SelectTrapDiscriminationChecker trapChecker = new SelectTrapDiscriminationChecker();

    public String generateGradingRubric(GenerateGradingRubricRequest request) {
        String sc = request.schemaContext();
        boolean hasForeignKey = sc != null && sc.toUpperCase(java.util.Locale.ROOT).contains("FOREIGN KEY");
        boolean hasReferences = sc != null && sc.toUpperCase(java.util.Locale.ROOT).contains("REFERENCES");
        log.info(
                "[generateGradingRubric] schemaContext length={} containsFOREIGN_KEY={} containsREFERENCES={}\n--- BEGIN schemaContext ---\n{}\n--- END schemaContext ---",
                sc != null ? sc.length() : 0, hasForeignKey, hasReferences, sc);
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

        String rubricJson = geminiService.generateGradingRubric(
                request.correctQuery(),
                request.questionContent(),
                request.totalPoints(),
                request.questionType(),
                priorQuestionContext,
                request.schemaContext());

        return rubricJson;
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
            String seedSchemaScript = payload.path("seed_schema_script")
                    .asText(rubric.path("seed_schema_script").asText(""));
            String dependsOnQuestionIdRaw = settings.path("depends_on_question_id").asText("").trim();
            boolean explicitWorkaround = settings.path("allow_cyclic_fk_workaround").asBoolean(false);
            boolean fallbackTriggered = false;
            List<Map<String, Object>> details = new ArrayList<>();
            List<ExamQuestionResponse> examQuestions = getExamQuestionsUsecase.execute(request.examId());

            for (int attempt = 1; attempt <= 2; attempt++) {
                details.clear();
                examSchemaService.resetSchema(teacherSchema, false);
                examSchemaService.resetSchema(studentSchema, false);

                String currentCorrectNormalized = normalizeSqlForExecution(correctQuery);
                List<Map<String, Object>> prepareDetails = new ArrayList<>();
                int teacherPrepared = executeExistingAnswersForSchema(
                        examQuestions,
                        teacherSchema,
                        prepareDetails,
                        null,
                        true,
                        currentCorrectNormalized);
                int studentPrepared = executeExistingAnswersForSchema(
                        examQuestions,
                        studentSchema,
                        prepareDetails,
                        null,
                        true,
                        currentCorrectNormalized);

                boolean prepareFailed = prepareDetails.stream()
                        .anyMatch(item -> "warning".equals(item.get("type")));
                if (prepareFailed) {
                    List<Map<String, Object>> earlyDetails = new ArrayList<>();
                    earlyDetails.add(Map.of(
                            "type", "error",
                            "message", "Không thể chuẩn bị schema nền từ các câu CREATE_TABLE trước khi chấm thử. "
                                    + "Vui lòng kiểm tra lại đáp án CREATE TABLE và thứ tự câu hỏi.",
                            "points", 0));
                    earlyDetails.addAll(prepareDetails);
                    return RubricTestGradeResponse.of(0, totalPoints, false, earlyDetails);
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

                if (fallbackTriggered) {
                    setAllConstraintsEnabled(teacherSchema, false);
                    setAllConstraintsEnabled(studentSchema, false);
                    details.add(Map.of(
                            "type", "info",
                            "message",
                            "Chạy bình thường bị lỗi khóa ngoại (FK constraint). Hệ thống TỰ ĐỘNG CHẠY LẠI và BẬT CHẾ ĐỘ WORKAROUND (tạm tắt ràng buộc) để tiếp tục chấm thử.",
                            "points", 0));
                } else if (explicitWorkaround) {
                    setAllConstraintsEnabled(teacherSchema, false);
                    setAllConstraintsEnabled(studentSchema, false);
                    details.add(Map.of(
                            "type", "info",
                            "message",
                            "Đang tự bật chế độ workaround FK vòng (NOCHECK CONSTRAINT) theo thiết lập rubric",
                            "points", 0));
                }

                boolean teacherFailed = false;
                try {
                    examSchemaService.executeSql(teacherSchema, correctQuery);
                } catch (Exception e) {
                    teacherFailed = true;
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

                if (attempt == 1 && !explicitWorkaround && !fallbackTriggered) {
                    boolean fkError = false;
                    if (compileError != null && (compileError.toLowerCase().contains("foreign key")
                            || compileError.toLowerCase().contains("ràng buộc")
                            || compileError.toLowerCase().contains("reference")
                            || compileError.toLowerCase().contains("conflict")
                            || compileError.toLowerCase().contains("khóa ngoại"))) {
                        fkError = true;
                    }
                    if (!fkError && teacherFailed) {
                        fkError = true;
                    }

                    if (fkError) {
                        fallbackTriggered = true;
                        continue;
                    }
                }

                if (explicitWorkaround || fallbackTriggered) {
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
                        String constraintError = "Vi phạm ràng buộc sau khi bật lại kiểm tra dữ liệu: "
                                + e.getMessage();
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
                                + " | Gợi ý: Bài làm đang vi phạm cập nhật dữ liệu do phụ thuộc khóa ngoại (có thể do cấu trúc). Hãy điều chỉnh lại cho phù hợp.";
                    }

                    fakeSubmission.setErrorMessage("Lỗi thực thi SQL: " + compileError);
                    details.add(Map.of(
                            "type", "error",
                            "message", "Lỗi thực thi: " + normalizedCompileError,
                            "points", 0));
                    fakeSubmission.setScoreEarned(BigDecimal.ZERO);
                } else {
                    insertDataGrader.gradeInsertDataByRubric(studentSchema, fakeQuestion, fakeSubmission,
                            fallbackTriggered);
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
                    details.add(Map.of("type", "success", "message", "Tất cả dữ liệu đều chính xác", "points",
                            totalPoints));
                }

                return RubricTestGradeResponse.of(
                        fakeSubmission.getScoreEarned() != null
                                ? fakeSubmission.getScoreEarned().doubleValue()
                                : 0,
                        totalPoints,
                        allPassed,
                        details);
            }
            throw new IllegalStateException("Unexpected flow in testGradeInsert");
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
            examSchemaService.resetSchema(caseSchema, false);

            List<Map<String, Object>> details = new ArrayList<>();
            bootstrapSelectSchema(
                    request.examId(),
                    examQuestions,
                    caseSchema,
                    details,
                    "RUN_TC");

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

            List<Map<String, Object>> teacherRows = examSchemaService.executeSql(caseSchema, correctQuery)
                    .getResultSet();

            List<ExecuteSelectTestCaseResponse.ColumnConfig> columnsConfig = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();

            if (teacherRows != null && !teacherRows.isEmpty()) {
                Map<String, Object> firstRow = teacherRows.get(0);
                for (String colName : firstRow.keySet()) {
                    columnsConfig.add(new ExecuteSelectTestCaseResponse.ColumnConfig(colName));
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
            examSchemaService.resetSchema(schemaName, false);

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

    public BuildCreateTablesResponse buildCreateTablesFromAnswer(Long examId, String correctQuery) {
        if (correctQuery == null || correctQuery.isBlank()) {
            throw new IllegalArgumentException("Script đáp án giáo viên (correctQuery) không được để trống.");
        }

        List<ExamQuestionResponse> examQuestions = getExamQuestionsUsecase.execute(examId);

        String schemaName = "test_build_create_" + System.currentTimeMillis();

        try {
            examSchemaService.resetSchema(schemaName, false);

            String normalizedCorrectSql = normalizeSqlForExecution(correctQuery);
            if (normalizedCorrectSql.isBlank()) {
                throw new IllegalArgumentException("SQL đáp án không hợp lệ sau khi chuẩn hóa.");
            }

            Set<String> currentCreatedTables = extractCreatedTableNames(normalizedCorrectSql);
            if (currentCreatedTables.isEmpty()) {
                currentCreatedTables = extractCreatedTableNames(correctQuery);
            }

            List<Map<String, Object>> details = new ArrayList<>();
            int preparedCount = executeExistingAnswersForSchema(
                    examQuestions,
                    schemaName,
                    details,
                    "BUILD_CREATE",
                    true,
                    normalizedCorrectSql,
                    currentCreatedTables);

            List<TableMetadata> baselineMetadata = examSchemaService.extractMetadata(schemaName);
            Set<String> baselineTableNames = new LinkedHashSet<>();
            for (TableMetadata tableMetadata : baselineMetadata) {
                if (tableMetadata == null || tableMetadata.getTableName() == null) {
                    continue;
                }
                baselineTableNames.add(tableMetadata.getTableName().toLowerCase(Locale.ROOT));
            }

            examSchemaService.executeSql(schemaName, normalizedCorrectSql);

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

            Set<String> targetTables = new LinkedHashSet<>(currentCreatedTables);

            if (targetTables.isEmpty()) {
                for (TableMetadata tableMetadata : metadataList) {
                    if (tableMetadata == null || tableMetadata.getTableName() == null) {
                        continue;
                    }
                    String normalizedName = tableMetadata.getTableName().toLowerCase(Locale.ROOT);
                    if (!baselineTableNames.contains(normalizedName)) {
                        targetTables.add(tableMetadata.getTableName());
                    }
                }
            }

            if (targetTables.isEmpty()) {
                throw new IllegalArgumentException(
                        "Không nhận diện được bảng CREATE TABLE từ SQL đáp án. Vui lòng kiểm tra lại correctQuery.");
            }

            List<BuildCreateTablesResponse.CreateTableConfig> tables = new ArrayList<>();
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

                List<BuildCreateTablesResponse.CreateColumnConfig> columns = new ArrayList<>();
                List<String> primaryKeyColumns = new ArrayList<>();
                Map<String, CreateForeignKeyGroup> foreignKeyGroups = new LinkedHashMap<>();

                for (TableMetadata.ColumnMetadata column : tableMetadata.getColumns()) {
                    columns.add(new BuildCreateTablesResponse.CreateColumnConfig(
                            column.getColumnName(),
                            column.getRawDataType(),
                            column.isNullable()));

                    if (column.isPrimaryKey()) {
                        primaryKeyColumns.add(column.getColumnName());
                    }

                    String referencesTable = column.getReferencesTable();
                    if (!column.isForeignKey() || referencesTable == null || referencesTable.isBlank()) {
                        continue;
                    }

                    String groupKey = referencesTable.trim().toLowerCase(Locale.ROOT);
                    CreateForeignKeyGroup group = foreignKeyGroups.computeIfAbsent(
                            groupKey,
                            key -> new CreateForeignKeyGroup(referencesTable.trim()));
                    group.columns().add(column.getColumnName());

                    String referencesColumn = column.getReferencesColumn();
                    if (referencesColumn != null && !referencesColumn.isBlank()) {
                        group.referencesColumns().add(referencesColumn);
                    }
                }

                List<BuildCreateTablesResponse.CreateConstraintConfig> constraints = new ArrayList<>();
                if (!primaryKeyColumns.isEmpty()) {
                    constraints.add(new BuildCreateTablesResponse.CreateConstraintConfig(
                            "PRIMARY_KEY",
                            List.copyOf(primaryKeyColumns),
                            null,
                            null));
                }

                for (CreateForeignKeyGroup group : foreignKeyGroups.values()) {
                    constraints.add(new BuildCreateTablesResponse.CreateConstraintConfig(
                            "FOREIGN_KEY",
                            List.copyOf(group.columns()),
                            group.referencesTable(),
                            group.referencesColumns().isEmpty() ? null : List.copyOf(group.referencesColumns())));
                }

                tables.add(new BuildCreateTablesResponse.CreateTableConfig(
                        tableMetadata.getTableName(),
                        columns,
                        constraints));
            }

            return new BuildCreateTablesResponse(
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
            JsonNode selectRules = resolveSelectGradingRules(rubric, payload);
            JsonNode testCases = payload.path("test_cases");
            JsonNode globalRules = payload.path("global_grading_rules");

            boolean strictOrdering = readBoolean(globalRules.path("strict_ordering"), false);

            if (testCases.isMissingNode() || !testCases.isArray() || testCases.size() == 0) {
                // No test_cases: mirror runtime, which grades by comparing the student query
                // against correctQuery across the spec datasets (gradeSelectByRubricTestCases
                // itself delegates here when test_cases is empty). Erroring out instead would
                // make the preview diverge from the real grade for correctQuery-only questions.
                return previewSelectAcrossDatasets(request, totalPoints);
            }

            BigDecimal totalDeduction = BigDecimal.ZERO;
            boolean allPassed = true;
            List<Map<String, Object>> details = new ArrayList<>();
            List<ExamQuestionResponse> examQuestions = getExamQuestionsUsecase.execute(request.examId());

            // Structural deduction (COLUMN rules) — applied ONCE, not per test case.
            // Column name/count issues are the same across all TCs, so we check once.
            BigDecimal structuralDeduction = BigDecimal.ZERO;
            boolean structuralChecked = false;

            for (int i = 0; i < testCases.size(); i++) {
                JsonNode tc = testCases.get(i);
                String caseId = tc.path("case_id").asText("TC_" + (i + 1));
                String caseName = tc.path("case_name").asText(caseId);
                double penaltyValue = tc.path("penalty_value").asDouble(1.0);
                BigDecimal caseMaxPenalty = BigDecimal.valueOf(penaltyValue).setScale(4, RoundingMode.HALF_UP);
                String casePhase = "setup";

                String caseSchema = "test_grade_select_case_" + System.currentTimeMillis() + "_" + i;
                try {
                    examSchemaService.resetSchema(caseSchema, false);

                    int bootstrappedTables = bootstrapSelectSchema(
                            request.examId(),
                            examQuestions,
                            caseSchema,
                            details,
                            caseId);
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

                    casePhase = "student_query";
                    List<Map<String, Object>> actualRows = examSchemaService.executeSql(caseSchema, studentQuery)
                            .getResultSet();
                    List<String> expectedColumns = new ArrayList<>();
                    List<List<String>> expectedRows = new ArrayList<>();

                    casePhase = "teacher_query";
                    if (!correctQuery.isBlank()) {
                        List<Map<String, Object>> teacherRows = examSchemaService.executeSql(caseSchema, correctQuery)
                                .getResultSet();

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

                        checkTrapDiscrimination(caseSchema, caseId, caseName, correctQuery, teacherRows, details);
                    } else {
                        JsonNode expectedResult = tc.path("expected_result");
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

                    // --- STRUCTURAL CHECK (once) ---
                    if (!structuralChecked && !actualRows.isEmpty() && !expectedColumns.isEmpty()) {
                        structuralChecked = true;
                        structuralDeduction = calculateSelectStructuralDeduction(
                                expectedColumns, actualRows, selectRules,
                                BigDecimal.valueOf(totalPoints), details);
                    }

                    casePhase = "grading";
                    BigDecimal caseDeduction = calculateSelectCaseDeductions(
                            caseId,
                            caseName,
                            caseMaxPenalty,
                            expectedColumns,
                            expectedRows,
                            actualRows,
                            strictOrdering,
                            selectRules,
                            details);

                    if (caseDeduction.compareTo(BigDecimal.ZERO) > 0) {
                        allPassed = false;
                        totalDeduction = totalDeduction.add(caseDeduction);
                    }
                } catch (Exception caseEx) {
                    if ("setup".equals(casePhase)) {
                        String setupErrorMessage = caseEx.getMessage() != null
                                ? caseEx.getMessage()
                                : "Lỗi không xác định";
                        details.add(Map.of(
                                "type", "warning",
                                "message", "[" + caseId + "] Lỗi chuẩn bị dữ liệu test case, bỏ qua không trừ điểm: "
                                        + setupErrorMessage,
                                "points", 0));
                        continue;
                    }
                    String errorMessage = caseEx.getMessage() != null ? caseEx.getMessage() : "Lỗi không xác định";
                    details.add(Map.of(
                            "type", "error",
                            "message", "[" + caseId + "] Lỗi chạy test case: " + errorMessage,
                            "points", -caseMaxPenalty.setScale(2, RoundingMode.HALF_UP).doubleValue()));
                    allPassed = false;
                    totalDeduction = totalDeduction.add(caseMaxPenalty);

                    if (errorMessage.contains("Invalid column name")) {
                        details.add(Map.of(
                                "type", "warning",
                                "message",
                                "[" + caseId + "] setup_custom_script đang dùng cột không tồn tại trong schema nền. "
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

            // Add structural deduction to total
            if (structuralDeduction.compareTo(BigDecimal.ZERO) > 0) {
                allPassed = false;
                totalDeduction = totalDeduction.add(structuralDeduction);
            }

            BigDecimal maxPoints = BigDecimal.valueOf(totalPoints);
            BigDecimal finalEarned = maxPoints.subtract(totalDeduction).setScale(2, RoundingMode.HALF_UP);

            if (finalEarned.compareTo(BigDecimal.ZERO) < 0) {
                finalEarned = BigDecimal.ZERO;
            }
            if (finalEarned.compareTo(maxPoints) > 0) {
                finalEarned = maxPoints;
            }

            return RubricTestGradeResponse.of(
                    finalEarned.doubleValue(),
                    totalPoints,
                    allPassed && totalDeduction.compareTo(BigDecimal.ZERO) <= 0,
                    details);

        } catch (Exception e) {
            throw new RuntimeException("Lỗi chấm thử SELECT: " + e.getMessage(), e);
        }
    }

    /**
     * Preview path for SELECT questions without explicit test_cases. Mirrors the runtime fallback
     * (SelectQuestionGrader.gradeSelectAcrossDatasets) so the teacher's "Chấm Giả Lập" matches the
     * real grade for correctQuery-only questions. Runs on a throwaway schema that the dataset grader
     * resets/populates itself; the schema is dropped afterwards.
     */
    private RubricTestGradeResponse previewSelectAcrossDatasets(TestGradeSelectRequest request, double totalPoints) {
        String correctQuery = request.correctQuery();
        if (correctQuery == null || correctQuery.isBlank()) {
            return RubricTestGradeResponse.of(
                    0,
                    totalPoints,
                    false,
                    List.of(Map.of(
                            "type", "error",
                            "message", "Rubric SELECT không có test_cases và thiếu đáp án mẫu (correctQuery) để chấm so sánh dataset",
                            "points", 0)));
        }

        Exam exam = examRepository.findById(request.examId()).orElse(null);
        ExamSpecification specification = (exam != null && exam.getSpecificationId() != null)
                ? examSpecificationRepository.findById(exam.getSpecificationId()).orElse(null)
                : null;

        ExamQuestion question = ExamQuestion.builder()
                .examId(request.examId())
                .questionType(QuestionType.SELECT_QUERY)
                .correctQuery(correctQuery)
                .points(BigDecimal.valueOf(totalPoints))
                .gradingRubric(request.gradingRubric())
                .build();

        String previewSchema = "rubric_test_select_" + request.examId() + "_" + System.currentTimeMillis();
        try {
            GradeDecision decision = selectGrader.gradeSelectAcrossDatasets(
                    specification, previewSchema, question, request.studentQuery());
            BigDecimal earned = decision.scoreEarned() != null ? decision.scoreEarned() : BigDecimal.ZERO;
            String message = decision.isCorrect()
                    ? "Chấm so sánh dataset: kết quả khớp đáp án mẫu"
                    : (decision.errorMessage() != null && !decision.errorMessage().isBlank()
                            ? decision.errorMessage()
                            : "Kết quả không khớp đáp án mẫu");
            return RubricTestGradeResponse.of(
                    earned.doubleValue(),
                    totalPoints,
                    decision.isCorrect(),
                    List.of(Map.of(
                            "type", decision.isCorrect() ? "success" : "error",
                            "message", message,
                            "points", earned.doubleValue())));
        } finally {
            try {
                examSchemaService.dropSchema(previewSchema);
            } catch (Exception ignore) {
            }
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
            examSchemaService.resetSchema(teacherSchema, false);
            examSchemaService.executeSql(teacherSchema, correctQuery);

            examSchemaService.resetSchema(studentSchema, false);
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

    public RubricTestGradeResponse testGradeRoutine(TestGradeRoutineRequest request) {
        String correctQuery = request.correctQuery();
        String studentQuery = request.studentQuery();
        String gradingRubric = request.gradingRubric();
        double totalPoints = request.totalPoints();

        String suffix = String.valueOf(System.currentTimeMillis());
        String teacherSchema = "test_grade_teacher_" + suffix;
        String studentSchema = "test_grade_student_" + suffix;
        String ddlScript = getExamDdlScript(request.examId());

        try {
            examSchemaService.resetSchema(teacherSchema, false);
            loadDdlIfPresent(teacherSchema, ddlScript);
            executeSetupThenRoutine(examSchemaService, teacherSchema, "", correctQuery);

            examSchemaService.resetSchema(studentSchema, false);
            loadDdlIfPresent(studentSchema, ddlScript);
            try {
                executeSetupThenRoutine(examSchemaService, studentSchema, "", studentQuery);
            } catch (Exception e) {
                return RubricTestGradeResponse.of(
                        0,
                        totalPoints,
                        false,
                        List.of(
                                Map.of("type", "error", "message",
                                        "Lỗi cú pháp SQL: " + e.getMessage(), "points", 0)));
            }

            return executeRoutineRubricGrading(
                    studentSchema, teacherSchema, gradingRubric, totalPoints);

        } catch (Exception e) {
            System.err.println("[DEBUG] Lỗi chấm thử routine: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Lỗi chấm thử Routine: " + e.getMessage(), e);
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

    private String getExamDdlScript(Long examId) {
        if (examId == null) {
            return "";
        }
        try {
            return examRepository.findById(examId)
                    .map(Exam::getSpecificationId)
                    .flatMap(examSpecificationRepository::findById)
                    .map(ExamSpecification::getDdlScript)
                    .orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void loadDdlIfPresent(String schemaName, String ddlScript) {
        if (ddlScript != null && !ddlScript.isBlank()) {
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, null);
        }
    }

    /**
     * Runs known-wrong mutants of the model answer on the trap data already loaded in
     * {@code caseSchema} and warns the teacher about any mutant the trap cannot distinguish from the
     * correct answer. Read-only against the schema; touches the response details only.
     */
    private void checkTrapDiscrimination(
            String caseSchema,
            String caseId,
            String caseName,
            String correctQuery,
            List<Map<String, Object>> teacherRows,
            List<Map<String, Object>> details) {
        List<SelectTrapDiscriminationChecker.Mutation> mutations = trapChecker.mutate(correctQuery);
        if (mutations.isEmpty()) {
            return;
        }

        List<String> nonDiscriminating = new ArrayList<>();
        for (SelectTrapDiscriminationChecker.Mutation mutation : mutations) {
            List<Map<String, Object>> mutantRows;
            try {
                mutantRows = examSchemaService.executeSql(caseSchema, mutation.mutatedSql()).getResultSet();
            } catch (Exception ex) {
                // A mutant that fails to run is already distinguishable from the answer -> trap is fine.
                continue;
            }
            if (trapChecker.sameResult(teacherRows, mutantRows)) {
                nonDiscriminating.add(mutation.label());
            }
        }

        if (!nonDiscriminating.isEmpty()) {
            details.add(Map.of(
                    "type", "warning",
                    "message", "[" + caseId + "] Bẫy \"" + caseName
                            + "\" CHƯA phân biệt được lỗi: " + String.join("; ", nonDiscriminating)
                            + ". Hãy bổ sung dữ liệu bẫy để câu sai cho kết quả khác đáp án mẫu.",
                    "points", 0));
        }
    }

    private int bootstrapSelectSchema(
            Long examId,
            List<ExamQuestionResponse> examQuestions,
            String schemaName,
            List<Map<String, Object>> details,
            String caseId) {
        SelectExamBootstrap bootstrap = resolveSelectExamBootstrap(examId);
        if (bootstrap.useExamDdl()) {
            examSchemaService.loadTemplateIntoSchema(
                    schemaName,
                    bootstrap.ddlScript(),
                    null);
            String prefix = caseId == null || caseId.isBlank() ? "" : "[" + caseId + "] ";
            details.add(Map.of(
                    "type", "info",
                    "message", prefix + "Đã nạp DDL của đặc tả đề thi",
                    "points", 0));
            return 0;
        }

        return executeExistingAnswersForSchema(
                examQuestions,
                schemaName,
                details,
                caseId,
                true);
    }

    private SelectExamBootstrap resolveSelectExamBootstrap(Long examId) {
        if (examId == null) {
            return SelectExamBootstrap.disabled();
        }

        Exam exam = examRepository.findById(examId).orElse(null);
        if (exam == null || exam.getSettings() == null || !Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl())) {
            return SelectExamBootstrap.disabled();
        }

        Long specificationId = exam.getSpecificationId();
        if (specificationId == null) {
            throw new BadRequestException("Đề thi đã bật nạp DDL nhưng không có specificationId.");
        }

        ExamSpecification specification = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new BadRequestException(
                        "Không tìm thấy specification " + specificationId + " của đề thi."));

        String ddlScript = specification.getDdlScript();
        if (ddlScript == null || ddlScript.isBlank()) {
            throw new BadRequestException("Đặc tả của đề thi không có DDL script để chạy test SELECT.");
        }

        return new SelectExamBootstrap(true, ddlScript);
    }

    private record SelectExamBootstrap(boolean useExamDdl, String ddlScript) {
        private static SelectExamBootstrap disabled() {
            return new SelectExamBootstrap(false, "");
        }
    }

    public RubricTestGradeResponse testGradeTrigger(TestGradeTriggerRequest request) {
        String correctQuery = request.correctQuery();
        String studentQuery = request.studentQuery();
        String gradingRubric = request.gradingRubric();
        double totalPoints = request.totalPoints();

        String suffix = String.valueOf(System.currentTimeMillis());
        String teacherSchema = "test_grade_teacher_" + suffix;
        String studentSchema = "test_grade_student_" + suffix;
        String ddlScript = getExamDdlScript(request.examId());

        try {
            String setupScript = extractSetupScriptFromRubric(gradingRubric);

            examSchemaService.resetSchema(teacherSchema, false);
            loadDdlIfPresent(teacherSchema, ddlScript);
            executeSetupThenRoutine(examSchemaService, teacherSchema, setupScript, correctQuery);

            examSchemaService.resetSchema(studentSchema, false);
            loadDdlIfPresent(studentSchema, ddlScript);
            try {
                executeSetupThenRoutine(examSchemaService, studentSchema, setupScript, studentQuery);
            } catch (Exception e) {
                return RubricTestGradeResponse.of(
                        0,
                        totalPoints,
                        false,
                        List.of(
                                Map.of("type", "error", "message",
                                        "Lỗi cú pháp SQL: " + e.getMessage(), "points", 0)));
            }

            return executeTriggerRubricGrading(
                    studentSchema, teacherSchema, gradingRubric, totalPoints, ddlScript, correctQuery, studentQuery);

        } catch (Exception e) {
            throw new RuntimeException("Lỗi chấm thử Trigger: " + e.getMessage(), e);
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

    private void executeSetupThenRoutine(ExamSchemaService service, String schemaName,
            String setupScript, String routineSql) {
        try {
            executeSqlScriptBatches(service, schemaName, setupScript);
            executeSqlScriptBatches(service, schemaName, routineSql);
        } catch (Exception e) {
            throw e;
        }
    }

    private void executeSqlScriptBatches(ExamSchemaService service, String schemaName, String sqlScript) {
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
                    service.executeSql(schemaName, normalizeDboReferences(executable, schemaName));
                }
            }
        }
    }

    private String normalizeDboReferences(String sql, String schemaName) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        // First, replace {SCHEMA} placeholder with schemaName (without brackets)
        String normalized = sql.replace("{SCHEMA}", schemaName);
        // Then replace dbo. with [schemaName].
        normalized = normalized.replaceAll("(?i)\\bdbo\\s*\\.", "[" + schemaName + "].");
        return normalized;
    }

    private List<String> splitBatchBeforeCreateRoutine(String batch) {
        if (batch == null || batch.isBlank()) {
            return List.of();
        }

        Matcher matcher = Pattern.compile(
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

    private void setAllConstraintsEnabled(String schemaName, boolean enabled) {
        String safeSchema = safeIdentifier(schemaName, "schemaName");
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

        normalized = normalized.replaceAll("(?s)/\\*.*?\\*/", " ");
        normalized = normalized.replaceAll("--[^\\r\\n]*", " ");

        normalized = normalized.replaceAll(
                "(?i)(CREATE\\s+TABLE|ALTER\\s+TABLE|INSERT\\s+INTO|UPDATE\\s+|DELETE\\s+FROM|MERGE\\s+INTO|DROP\\s+TABLE|TRUNCATE\\s+TABLE|WITH\\s+)",
                "\n$1");

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
        return executeExistingAnswersForSchema(
                examQuestions,
                schemaName,
                details,
                caseId,
                createTableOnly,
                excludeNormalizedSql,
                Set.of());
    }

    private int executeExistingAnswersForSchema(
            List<ExamQuestionResponse> examQuestions,
            String schemaName,
            List<Map<String, Object>> details,
            String caseId,
            boolean createTableOnly,
            String excludeNormalizedSql,
            Set<String> excludeCreatedTableNames) {
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
            if (createTableOnly && hasCreatedTableNameOverlap(normalizedSql, excludeCreatedTableNames)) {
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

    private boolean hasCreatedTableNameOverlap(String sql, Set<String> tableNames) {
        if (sql == null || sql.isBlank() || tableNames == null || tableNames.isEmpty()) {
            return false;
        }

        Set<String> normalizedTableNames = new HashSet<>();
        for (String tableName : tableNames) {
            if (tableName != null && !tableName.isBlank()) {
                normalizedTableNames.add(tableName.toLowerCase(Locale.ROOT));
            }
        }

        for (String createdTableName : extractCreatedTableNames(sql)) {
            if (createdTableName != null
                    && normalizedTableNames.contains(createdTableName.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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

            details.add(Map.of(
                    "type", "info",
                    "message", "[" + caseId
                            + "] Dọn dữ liệu tạm trước khi chạy lại setup_custom_script để tránh trùng khóa.",
                    "points", 0));
            clearAllDataInSchema(schemaName);

            setAllConstraintsEnabled(schemaName, false);
            try {
                try {
                    examSchemaService.executeSql(schemaName, setupScript);
                } catch (Exception retryEx) {
                    String retryMessage = retryEx.getMessage() != null ? retryEx.getMessage() : "";
                    boolean retryFkConflict = retryMessage.contains("FOREIGN KEY constraint");
                    String relaxedScript = stripRecheckConstraintStatements(setupScript);

                    if (!retryFkConflict
                            || relaxedScript.isBlank()
                            || relaxedScript.equals(setupScript)) {
                        throw retryEx;
                    }

                    details.add(Map.of(
                            "type", "warning",
                            "message", "[" + caseId
                                    + "] setup_custom_script chứa lệnh CHECK CONSTRAINT gây lỗi FK khi thử lại. "
                                    + "Hệ thống tự bỏ lệnh CHECK để tiếp tục dựng dữ liệu test case.",
                            "points", 0));

                    clearAllDataInSchema(schemaName);
                    setAllConstraintsEnabled(schemaName, false);
                    examSchemaService.executeSql(schemaName, relaxedScript);
                }
            } finally {
                try {
                    setAllConstraintsEnabled(schemaName, true);
                } catch (Exception recheckEx) {
                    String recheckMessage = recheckEx.getMessage() != null ? recheckEx.getMessage() : "";
                    boolean fkStillInvalid = recheckMessage.contains("FOREIGN KEY constraint");
                    if (!fkStillInvalid) {
                        throw recheckEx;
                    }

                    details.add(Map.of(
                            "type", "warning",
                            "message", "[" + caseId
                                    + "] setup_custom_script còn vi phạm FK sau khi nạp dữ liệu. "
                                    + "Tiếp tục chấm test case ở chế độ NOCHECK CONSTRAINT cho schema tạm.",
                            "points", 0));

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

            String normalized = statement
                    .replaceAll("\\s+", " ")
                    .trim()
                    .toUpperCase(Locale.ROOT);

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
        String safeSchema = safeIdentifier(schemaName, "schemaName");
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
                String tableName = safeIdentifier(String.valueOf(tableNameObj), "tableName");
                examSchemaService.executeAdminSql(
                        "DELETE FROM [" + safeSchema + "].[" + tableName + "]");
            }
        } finally {
            setAllConstraintsEnabled(safeSchema, true);
        }
    }

    /**
     * Checks column-level (structural) violations ONCE and returns the total
     * deduction.
     * These rules apply to the query's column structure, which is the same across
     * all test cases.
     */
    private BigDecimal calculateSelectStructuralDeduction(
            List<String> expectedColumns,
            List<Map<String, Object>> actualRows,
            JsonNode selectRules,
            BigDecimal maxTotalPoints,
            List<Map<String, Object>> details) {

        List<String> actualColumns = actualRows.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(actualRows.get(0).keySet());

        List<String> effectiveExpectedColumns = new ArrayList<>();
        if (expectedColumns != null) {
            for (String col : expectedColumns) {
                if (col != null && !col.isBlank()) {
                    effectiveExpectedColumns.add(col);
                }
            }
        }

        if (effectiveExpectedColumns.isEmpty() || actualColumns.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // Positional column analysis
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
        if (nameMismatchAtSamePosition == 0 && trulyMissingColumns == 0 && trulyExtraColumns == 0
                && !sameColumnOrder(effectiveExpectedColumns, actualColumns)) {
            columnOrderViolations = 1;
        }

        // No structural issues
        if (nameMismatchAtSamePosition == 0 && trulyMissingColumns == 0 && extraColumns == 0
                && columnOrderViolations == 0) {
            return BigDecimal.ZERO;
        }

        // Build structural rule checks:
        // - COLUMN/NOT_EQUAL: column exists at same position but has different name
        // (e.g., missing alias)
        // - COLUMN/IS_MISSING: column is truly missing (student has fewer columns)
        // - COLUMN/IS_EXTRA: student has extra columns
        // - COLUMN_ORDER/OUT_OF_ORDER: columns are reordered
        List<SelectRuleApplication> structuralApps = new ArrayList<>();
        if (nameMismatchAtSamePosition > 0) {
            structuralApps.add(applySelectRule(selectRules, "COLUMN", "NOT_EQUAL", nameMismatchAtSamePosition,
                    maxTotalPoints, 0.0,
                    "t\u00ean " + nameMismatchAtSamePosition + " c\u1ed9t kh\u00f4ng kh\u1edbp \u0111\u00e1p \u00e1n"));
        }
        if (trulyMissingColumns > 0) {
            structuralApps.add(applySelectRule(selectRules, "COLUMN", "IS_MISSING", trulyMissingColumns,
                    maxTotalPoints, 0.0,
                    "thi\u1ebfu " + trulyMissingColumns + " c\u1ed9t"));
        }
        if (extraColumns > 0) {
            structuralApps.add(applySelectRule(selectRules, "COLUMN", "IS_EXTRA", extraColumns,
                    maxTotalPoints, 0.0,
                    "th\u1eeba " + extraColumns + " c\u1ed9t"));
        }
        if (columnOrderViolations > 0) {
            structuralApps.add(applySelectRule(selectRules, "COLUMN_ORDER", "OUT_OF_ORDER", columnOrderViolations,
                    maxTotalPoints, 0.0,
                    "sai th\u1ee9 t\u1ef1 c\u1ed9t k\u1ebft qu\u1ea3"));
        }

        double totalStructuralDeduction = 0d;
        StringBuilder issueBuilder = new StringBuilder();
        boolean anyViolation = false;

        for (SelectRuleApplication app : structuralApps) {
            if (!app.violationPresent())
                continue;
            anyViolation = true;
            if (app.deduction().compareTo(BigDecimal.ZERO) > 0) {
                totalStructuralDeduction += app.deduction().doubleValue();
            }
            if (app.message() != null && !app.message().isBlank()) {
                appendSelectIssue(issueBuilder, app.message());
            }
        }

        if (!anyViolation) {
            return BigDecimal.ZERO;
        }

        // If violations exist but no rules matched (totalDeduction=0 with rule not
        // found),
        // still report the issue as info so teacher can see it
        if (totalStructuralDeduction <= 0) {
            String unmatchedMsg = "Ph\u00e1t hi\u1ec7n kh\u00e1c bi\u1ec7t c\u1ed9t k\u1ebft qu\u1ea3";
            if (missingColumns > 0) {
                unmatchedMsg += " (thi\u1ebfu/sai t\u00ean " + missingColumns + " c\u1ed9t)";
            }
            if (extraColumns > 0) {
                unmatchedMsg += " (th\u1eeba " + extraColumns + " c\u1ed9t)";
            }
            unmatchedMsg += " nh\u01b0ng kh\u00f4ng t\u00ecm th\u1ea5y quy t\u1eafc COLUMN t\u01b0\u01a1ng \u1ee9ng \u2192 kh\u00f4ng tr\u1eeb \u0111i\u1ec3m.";
            details.add(Map.of(
                    "type", "info",
                    "message", "[\u0110i\u1ec3m c\u1ea5u tr\u00fac c\u1ed9t] " + unmatchedMsg,
                    "points", 0));
            return BigDecimal.ZERO;
        }

        // Cap at total points
        if (totalStructuralDeduction > maxTotalPoints.doubleValue()) {
            totalStructuralDeduction = maxTotalPoints.doubleValue();
        }

        BigDecimal rounded = BigDecimal.valueOf(totalStructuralDeduction).setScale(2, RoundingMode.HALF_UP);
        details.add(Map.of(
                "type", "warning",
                "message", "[\u0110i\u1ec3m c\u1ea5u tr\u00fac c\u1ed9t] " + issueBuilder.toString().trim()
                        + " \u2192 Tr\u1eeb " + rounded
                        + " \u0111i\u1ec3m (\u00e1p d\u1ee5ng 1 l\u1ea7n cho to\u00e0n b\u00e0i)",
                "points", -rounded.doubleValue()));

        return rounded;
    }

    private BigDecimal calculateSelectCaseDeductions(
            String caseId,
            String caseName,
            BigDecimal caseMaxPenalty,
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

        // Remap actual rows by column position so cell comparison works
        // even when student uses different column aliases (e.g., missing AS).
        // Column name mismatches are still tracked separately via COLUMN rules.
        List<Map<String, Object>> remappedActualRows = remapActualRowsByPosition(
                safeActualRows, actualColumns, effectiveExpectedColumns);

        if (compareSelectResultStrict(remappedActualRows, expectedRowMaps, strictOrdering, comparisonColumns)) {
            details.add(Map.of(
                    "type", "success",
                    "message", "[" + caseId + "] " + caseName + ": Khớp hoàn toàn kết quả, không bị trừ điểm",
                    "points", 0));
            return BigDecimal.ZERO;
        }

        int expectedRowsCount = expectedRowMaps.size();
        int actualRowsCount = safeActualRows.size();
        int missingRows = Math.max(0, expectedRowsCount - actualRowsCount);
        int extraRows = Math.max(0, actualRowsCount - expectedRowsCount);

        JsonNode rowOrderRule = findSelectRule(selectRules, "ROW_ORDER", "OUT_OF_ORDER");
        int rowOrderViolations = 0;
        if (strictOrdering && rowOrderRule != null && !hasSelectModifier(rowOrderRule, "SORT_ASC")) {
            rowOrderViolations = countSelectRowOrderViolations(remappedActualRows, expectedRowMaps, comparisonColumns);
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
                remappedActualRows,
                expectedRowMaps,
                comparisonColumns,
                strictOrdering,
                cellCompareModifiers);
        int wrongCells = countSelectCellMismatches(rowPairs, comparisonColumns, cellCompareModifiers);
        int nullViolations = countSelectNullViolations(rowPairs, comparisonColumns, cellCompareModifiers);

        List<SelectRuleApplication> applications = List.of(
                applySelectRule(selectRules, "ROW", "IS_MISSING", missingRows,
                        caseMaxPenalty, 0.0,
                        "thiếu " + missingRows + " dòng kết quả"),
                applySelectRule(selectRules, "ROW", "IS_EXTRA", extraRows,
                        caseMaxPenalty, 0.0,
                        "thừa " + extraRows + " dòng kết quả"),
                applySelectRule(selectRules, "CELL_VALUE", "NOT_EQUAL", wrongCells,
                        caseMaxPenalty, 0.0,
                        "sai giá trị " + wrongCells + " ô dữ liệu"),
                applySelectRule(selectRules, "CELL_VALUE", "IS_NULL", nullViolations,
                        caseMaxPenalty, 0.0,
                        nullViolations + " ô dữ liệu bị rỗng/NULL"),
                applySelectRule(selectRules, "ROW_ORDER", "OUT_OF_ORDER", rowOrderViolations,
                        caseMaxPenalty, 0.0,
                        "sai thứ tự " + rowOrderViolations + " dòng"));
        // NOTE: COLUMN rules (IS_MISSING, IS_EXTRA, COLUMN_ORDER) are handled
        // once in calculateSelectStructuralDeduction, not per test case.

        double totalCaseDeduction = 0d;
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
                totalCaseDeduction += application.deduction().doubleValue();
            }

            if (application.message() != null && !application.message().isBlank()) {
                appendSelectIssue(issueBuilder, application.message());
            }
        }

        if (failAllTriggered) {
            totalCaseDeduction = caseMaxPenalty.doubleValue();
        } else if (matchedRuleCount == 0 && totalCaseDeduction <= 0) {
            // Violations exist but no rules matched → don't deduct,
            // just report. Teacher didn't configure rules for these violations.
            appendSelectIssue(issueBuilder,
                    "Ph\u00e1t hi\u1ec7n sai l\u1ec7ch nh\u01b0ng kh\u00f4ng c\u00f3 quy t\u1eafc ch\u1ea5m ph\u00f9 h\u1ee3p \u2192 kh\u00f4ng tr\u1eeb \u0111i\u1ec3m.");
        }

        if (totalCaseDeduction > caseMaxPenalty.doubleValue()) {
            totalCaseDeduction = caseMaxPenalty.doubleValue();
        }

        BigDecimal caseDeductionValue = BigDecimal.valueOf(totalCaseDeduction).setScale(8, RoundingMode.HALF_UP);
        BigDecimal delta = caseDeductionValue.abs();
        boolean allChecksPassed = !failAllTriggered && delta.compareTo(new BigDecimal("0.0001")) <= 0;

        BigDecimal roundedDeduction = caseDeductionValue.setScale(2, RoundingMode.HALF_UP);
        String scoreMessage = "[" + caseId + "] " + caseName
                + (allChecksPassed ? ": Khớp một phần hợp lệ, trừ 0 điểm" : ": Bị trừ " + roundedDeduction + " điểm");

        details.add(Map.of(
                "type", allChecksPassed ? "success" : "warning",
                "message", scoreMessage,
                "points", -roundedDeduction.doubleValue()));

        if (!allChecksPassed) {
            String issueMessage = issueBuilder.length() == 0
                    ? "Kết quả SELECT không khớp rubric chấm điểm."
                    : issueBuilder.toString().trim();
            details.add(Map.of(
                    "type", "error",
                    "message", "[" + caseId + "] " + issueMessage,
                    "points", 0));
        }

        return roundedDeduction;
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
            String message = "Quy tắc " + ruleLabel + " bỏ qua vi phạm (" + violationSummary + ").";
            return SelectRuleApplication.matchedViolation(false, BigDecimal.ZERO, message);
        }

        if (decision.failAll()) {
            String message = "Quy tắc " + ruleLabel + " kích hoạt FAIL_ALL (" + violationSummary + ").";
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

        String actionLabel;
        switch (action.toUpperCase(Locale.ROOT)) {
            case "IGNORE":
                actionLabel = "bỏ qua";
                break;
            case "FAIL_ALL":
                actionLabel = "trượt toàn bộ";
                break;
            default:
                actionLabel = "trừ điểm";
                break;
        }

        return String.format(
                "Phát hiện %s → %s %s điểm",
                violationSummary,
                actionLabel,
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

    /**
     * Remap actual row values to use expected column names by position.
     * This allows cell-level comparison to work even when student uses different
     * column aliases (e.g., missing AS clause). Column name differences are
     * tracked separately via COLUMN/IS_MISSING and COLUMN/IS_EXTRA rules.
     */
    private List<Map<String, Object>> remapActualRowsByPosition(
            List<Map<String, Object>> actualRows,
            List<String> actualColumns,
            List<String> expectedColumns) {
        if (actualRows == null || actualRows.isEmpty()
                || expectedColumns == null || expectedColumns.isEmpty()) {
            return actualRows != null ? actualRows : List.of();
        }

        // If columns already match by name (case-insensitive), no remap needed
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

        // Remap: for each actual row, create a new map keyed by expected column names
        // with values taken from the actual row at the same column position
        List<Map<String, Object>> remapped = new ArrayList<>();
        for (Map<String, Object> actualRow : actualRows) {
            Map<String, Object> newRow = new LinkedHashMap<>();
            for (int i = 0; i < expectedColumns.size(); i++) {
                String expectedCol = expectedColumns.get(i);
                Object value;
                if (i < actualColumns.size()) {
                    value = getRowValueIgnoreCase(actualRow, actualColumns.get(i));
                } else {
                    value = null;
                }
                newRow.put(expectedCol, value);
            }
            remapped.add(newRow);
        }
        return remapped;
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
                    List.of(Map.of("type", "error", "message", "Rubric JSON không hợp lệ", "points", 0)),
                    0d);
        }

        List<TableMetadata> actualTables = examSchemaService.extractMetadata(studentSchema);
        CreateTableRubricEvaluator.CreateTableRubricGradeResult result = CreateTableRubricEvaluator.evaluate(
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

    private Set<String> extractCreatedTableNames(String sql) {
        Set<String> tableNames = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) {
            return tableNames;
        }

        Matcher matcher = CREATE_TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String rawIdentifier = matcher.group(1);
            String tableName = extractLastIdentifier(rawIdentifier);
            if (tableName != null && !tableName.isBlank()) {
                tableNames.add(tableName);
            }
        }

        return tableNames;
    }

    private static final class CreateForeignKeyGroup {
        private final String referencesTable;
        private final List<String> columns;
        private final List<String> referencesColumns;

        private CreateForeignKeyGroup(String referencesTable) {
            this.referencesTable = referencesTable;
            this.columns = new ArrayList<>();
            this.referencesColumns = new ArrayList<>();
        }

        private String referencesTable() {
            return referencesTable;
        }

        private List<String> columns() {
            return columns;
        }

        private List<String> referencesColumns() {
            return referencesColumns;
        }
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

            String message = deduction > 0d
                    ? String.format(
                            Locale.ROOT,
                            "Bảng %s: thiếu %d dòng, sai %d ô, dư %d dòng, sai thứ tự %d dòng, trừ %.2f điểm.",
                            tableName,
                            missingRows,
                            wrongCells,
                            extraRows,
                            outOfOrderRows,
                            deduction)
                    : String.format(
                            Locale.ROOT,
                            "Bảng %s: thiếu %d dòng, sai %d ô, dư %d dòng, sai thứ tự %d dòng.",
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
            throw new IllegalArgumentException("Định danh SQL không hợp lệ cho " + fieldName);
        }
        return identifier;
    }

    /**
     * Parses the {@code routines[]} array from the AI rubric into RoutineMetadata.
     * Returns an empty list when the rubric omits routines or any entry is
     * malformed — caller falls back to teacher schema metadata in that case.
     *
     * <p>
     * Why parse from rubric, not teacher schema: the rubric is the
     * authoritative answer for "which routines the QUESTION requires", and
     * intentionally excludes helper FN/SP that the reference SQL uses
     * internally as implementation detail.
     */
    /**
     * Normalize routine type from rubric to match what
     * INFORMATION_SCHEMA.ROUTINES.ROUTINE_TYPE returns ("PROCEDURE" / "FUNCTION").
     * AI thường trả "STORED_PROCEDURE" theo enum domain — quy về "PROCEDURE"
     * để khớp với metadata extract từ MSSQL, tránh log "Sai loại routine" oan.
     */
    private static String normalizeRoutineType(String raw) {
        if (raw == null)
            return null;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if ("STORED_PROCEDURE".equals(upper) || "SQL_STORED_PROCEDURE".equals(upper)) {
            return "PROCEDURE";
        }
        if ("SCALAR_FUNCTION".equals(upper) || "TABLE_VALUED_FUNCTION".equals(upper)
                || "SQL_SCALAR_FUNCTION".equals(upper) || "SQL_TABLE_VALUED_FUNCTION".equals(upper)) {
            return "FUNCTION";
        }
        return upper;
    }

    private boolean isStoredProcedureRubric(JsonNode rubric, List<RoutineMetadata> expectedRoutines) {
        String category = rubric.path("question_category").asText("");
        if ("STORED_PROCEDURE".equalsIgnoreCase(category)) {
            return true;
        }
        if ("FUNCTION".equalsIgnoreCase(category)) {
            return false;
        }
        return expectedRoutines != null
                && !expectedRoutines.isEmpty()
                && expectedRoutines.stream()
                        .allMatch(r -> "PROCEDURE".equalsIgnoreCase(normalizeRoutineType(r.getRoutineType())));
    }

    private List<RoutineMetadata> parseExpectedRoutinesFromRubric(JsonNode routinesNode) {
        if (routinesNode == null || !routinesNode.isArray() || routinesNode.isEmpty()) {
            return List.of();
        }
        List<RoutineMetadata> result = new ArrayList<>();
        for (JsonNode r : routinesNode) {
            String name = r.path("expected_name").asText("").trim();
            if (name.isBlank())
                continue;
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
    }

    private RubricTestGradeResponse executeRoutineRubricGrading(
            String studentSchema,
            String teacherSchema,
            String gradingRubricJson,
            double totalPoints) {

        List<Map<String, Object>> details = new ArrayList<>();

        try {
            JsonNode rubric = objectMapper.readTree(gradingRubricJson);
            JsonNode gradingPayload = rubric.path("grading_payload");
            JsonNode routines = gradingPayload.path("routines");
            JsonNode testCases = gradingPayload.path("test_cases");
            JsonNode gradingSettings = gradingPayload.path("grading_settings");

            boolean positiveOnlyScoring = gradingSettings.path("positive_only_scoring").asBoolean(false);
            boolean caseSensitiveNames = gradingSettings.path("case_sensitive_names").asBoolean(false);
            String printOutputCompareMode = gradingSettings.path("print_output_compare_mode").asText("LENIENT");

            // Source of truth for "what routines the question requires" is the AI
            // rubric, not the teacher schema. Teacher's correctQuery may contain
            // helper FN/SP as implementation detail (e.g. an FN_NextId helper
            // called from inside the main SP); the student is free to inline /
            // CTE / take a different approach. Penalizing — or even reporting
            // success on — those helpers misleads the teacher about what the
            // grader actually checks.
            // See md/GRAD-141_SP_GRADING_GAPS.md §2 LỖ HỔNG 1.
            List<RoutineMetadata> expectedRoutines = parseExpectedRoutinesFromRubric(routines);
            if (expectedRoutines.isEmpty()) {
                // Legacy questions whose rubric omits routines[] fall back to
                // teacher schema metadata to preserve old behavior.
                expectedRoutines = examSchemaService.extractRoutineMetadata(teacherSchema);
            }
            List<RoutineMetadata> actualRoutines = examSchemaService.extractRoutineMetadata(studentSchema);

            double earnedPoints = 0;
            boolean allPassed = true;

            if (expectedRoutines.isEmpty()) {
                details.add(Map.of(
                        "type", "error",
                        "message", "Không tìm thấy routine trong đáp án chuẩn",
                        "points", 0));
                return RubricTestGradeResponse.of(0, totalPoints, false, details);
            }

            boolean hasTestCases = testCases.isArray() && testCases.size() > 0;
            boolean metadataDiagnosticOnly = hasTestCases
                    && isStoredProcedureRubric(rubric, expectedRoutines);
            double metadataMaxPoints = metadataDiagnosticOnly ? 0 : (hasTestCases ? totalPoints * 0.20 : totalPoints);
            double testCaseMaxPoints = metadataDiagnosticOnly ? totalPoints : (hasTestCases ? totalPoints * 0.80 : 0);
            double perRoutineWeight = metadataMaxPoints / expectedRoutines.size();

            for (RoutineMetadata expected : expectedRoutines) {
                RoutineMetadata actual = actualRoutines.stream()
                        .filter(r -> caseSensitiveNames
                                ? r.getRoutineName().equals(expected.getRoutineName())
                                : r.getRoutineName().equalsIgnoreCase(expected.getRoutineName()))
                        .findFirst()
                        .orElse(null);

                if (actual == null) {
                    details.add(Map.of(
                            "type", "error",
                            "message",
                            String.format("Thiếu %s %s", expected.getRoutineType(), expected.getRoutineName()),
                            "points", 0));
                    if (!metadataDiagnosticOnly) {
                        allPassed = false;
                    }
                    continue;
                }

                double routineScore = perRoutineWeight;

                if (!normalizeRoutineType(expected.getRoutineType())
                        .equalsIgnoreCase(normalizeRoutineType(actual.getRoutineType()))) {
                    details.add(Map.of(
                            "type", "warning",
                            "message", String.format("Sai loại routine %s (kỳ vọng: %s, thực tế: %s)",
                                    expected.getRoutineName(), expected.getRoutineType(), actual.getRoutineType()),
                            "points", (int) (-perRoutineWeight * 0.3)));
                    routineScore *= 0.7;
                    if (!metadataDiagnosticOnly) {
                        allPassed = false;
                    }
                }

                if (expected.getParameters().size() != actual.getParameters().size()) {
                    details.add(Map.of(
                            "type", "warning",
                            "message", String.format("Sai số lượng tham số ở %s (kỳ vọng: %d, thực tế: %d)",
                                    expected.getRoutineName(), expected.getParameters().size(),
                                    actual.getParameters().size()),
                            "points", (int) (-perRoutineWeight * 0.2)));
                    routineScore *= 0.8;
                    if (!metadataDiagnosticOnly) {
                        allPassed = false;
                    }
                }

                earnedPoints += routineScore;
                details.add(Map.of(
                        "type", "success",
                        "message", String.format("%s %s: đúng", expected.getRoutineType(), expected.getRoutineName()),
                        "points", (int) routineScore));
            }

            if (hasTestCases) {
                RoutineTestCaseGrade routineTcGrade = executeRoutineTestCases(
                        studentSchema,
                        teacherSchema,
                        testCases,
                        testCaseMaxPoints,
                        printOutputCompareMode,
                        details);
                earnedPoints += routineTcGrade.earnedPoints();
                allPassed = metadataDiagnosticOnly
                        ? routineTcGrade.allPassed()
                        : allPassed && routineTcGrade.allPassed();

                /*
                 * double testCaseWeight = totalPoints * 0.3;
                 * double perTestCase = testCaseWeight / testCases.size();
                 * 
                 * for (JsonNode tc : List.<JsonNode>of()) {
                 * String caseName = tc.path("case_name").asText("Unnamed");
                 * double penaltyValue = tc.path("penalty_value").asDouble(0.5);
                 * 
                 * details.add(Map.of(
                 * "type", "info",
                 * "message",
                 * String.format("Test case '%s': chưa thực thi (cần triển khai thêm)",
                 * caseName),
                 * "points", 0));
                 * }
                 */
            }

            double finalScore = Math.min(earnedPoints, totalPoints);
            if (positiveOnlyScoring && finalScore < 0) {
                finalScore = 0;
            }

            return RubricTestGradeResponse.of(finalScore, totalPoints, allPassed, details);

        } catch (Exception e) {
            details.add(Map.of(
                    "type", "error",
                    "message", "Lỗi phân tích rubric: " + e.getMessage(),
                    "points", 0));
            return RubricTestGradeResponse.of(0, totalPoints, false, details);
        }
    }

    private static final String VALIDATION_MARKER_COLUMN = "__VALIDATION_MARKER__";

    private RoutineTestCaseGrade executeRoutineTestCases(
            String studentSchema,
            String teacherSchema,
            JsonNode testCases,
            double maxPoints,
            String printOutputCompareMode,
            List<Map<String, Object>> details) {
        double totalWeight = 0;
        for (JsonNode tc : testCases) {
            totalWeight += Math.abs(readTestCaseWeight(tc));
        }
        if (totalWeight <= 0) {
            totalWeight = testCases.size();
        }

        double earned = 0;
        boolean allPassed = true;

        for (int i = 0; i < testCases.size(); i++) {
            JsonNode tc = testCases.get(i);
            String caseName = textOrDefault(tc, "case_name", "TC" + (i + 1));
            double normalizedWeight = Math.abs(readTestCaseWeight(tc));
            if (normalizedWeight <= 0) {
                normalizedWeight = 1;
            }
            double casePoints = maxPoints * (normalizedWeight / totalWeight);

            try {
                String expected = runRoutineTestCase(teacherSchema, teacherSchema, tc);
                String actual = runRoutineTestCase(studentSchema, teacherSchema, tc);
                boolean passed = compareRoutineTestCase(
                        actual,
                        expected,
                        textOrDefault(tc, "match_type", "EXACT"),
                        textOrDefault(tc, "verification_type", "RETURN_VALUE"),
                        printOutputCompareMode);

                if (passed) {
                    earned += casePoints;
                    details.add(Map.of(
                            "type", "success",
                            "message", String.format("Test case '%s': đúng", caseName),
                            "points", roundTo2(casePoints)));
                } else {
                    allPassed = false;
                    details.add(Map.of(
                            "type", "error",
                            "message", String.format("Test case '%s': mong đợi='%s', thực tế='%s'",
                                    caseName, truncateForDetail(expected), truncateForDetail(actual)),
                            "points", 0));
                }
            } catch (RoutineTestCaseExecutionException e) {
                allPassed = false;
                details.add(Map.of(
                        "type", "error",
                        "message", String.format("Test case '%s': lỗi thực thi: %s | SQL: %s",
                                caseName,
                                e.getMessage(),
                                truncateForDetail(e.sql())),
                        "points", 0));
            } catch (Exception e) {
                allPassed = false;
                details.add(Map.of(
                        "type", "error",
                        "message", String.format("Test case '%s': lỗi thực thi: %s", caseName, e.getMessage()),
                        "points", 0));
            }
        }

        return new RoutineTestCaseGrade(earned, allPassed);
    }

    private String runRoutineTestCase(String targetSchema, String teacherSchema, JsonNode tc) {
        String setup = resolveRoutineSql(textOrNull(tc, "setup_script"), targetSchema, teacherSchema);
        String invocation = resolveRoutineSql(textOrNull(tc, "invocation_query"), targetSchema, teacherSchema);
        String validation = resolveRoutineSql(textOrNull(tc, "validation_query"), targetSchema, teacherSchema);
        String verificationType = textOrDefault(tc, "verification_type", "RETURN_VALUE").toUpperCase(Locale.ROOT);
        boolean printOutput = "PRINT_OUTPUT".equals(verificationType);

        if (!printOutput && (validation == null || validation.isBlank())) {
            throw new IllegalArgumentException(
                    "validation_query là bắt buộc cho verification_type=" + verificationType);
        }

        StringBuilder batch = new StringBuilder();
        batch.append("BEGIN TRY\n");
        batch.append("  BEGIN TRANSACTION;\n");
        appendSqlStatement(batch, setup);
        appendSqlStatement(batch, invocation);
        if (!printOutput && validation != null && !validation.isBlank()) {
            batch.append("  SELECT NULL AS ").append(VALIDATION_MARKER_COLUMN).append(";\n");
            appendSqlStatement(batch, validation);
        }
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append("END TRY\n");
        batch.append("BEGIN CATCH\n");
        batch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
        batch.append("  THROW;\n");
        batch.append("END CATCH;");

        String batchSql = batch.toString();
        SqlExecutionResult result;
        try {
            result = examSchemaService.executeSqlBatchAsSchemaUser(targetSchema, batchSql);
        } catch (Exception e) {
            throw new RoutineTestCaseExecutionException(e.getMessage(), batchSql, e);
        }
        if (printOutput) {
            List<String> prints = result != null && result.getPrintMessages() != null
                    ? result.getPrintMessages()
                    : List.of();
            return String.join("\n", prints).trim();
        }
        return serializeRoutineResult(dropRowsBeforeValidationMarker(result));
    }

    private void appendSqlStatement(StringBuilder batch, String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        batch.append("  ").append(sql).append(";\n");
    }

    private SqlExecutionResult dropRowsBeforeValidationMarker(SqlExecutionResult result) {
        if (result == null || result.getResultSet() == null) {
            return result;
        }
        List<Map<String, Object>> rows = result.getResultSet();
        int markerIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).containsKey(VALIDATION_MARKER_COLUMN)) {
                markerIndex = i;
                break;
            }
        }
        if (markerIndex < 0) {
            return result;
        }
        List<Map<String, Object>> filtered = new ArrayList<>(rows.subList(markerIndex + 1, rows.size()));
        return SqlExecutionResult.builder()
                .resultSet(filtered)
                .rowCount(filtered.size())
                .statusMessage(result.getStatusMessage())
                .printMessages(result.getPrintMessages())
                .build();
    }

    private String serializeRoutineResult(SqlExecutionResult result) {
        if (result == null || result.getResultSet() == null || result.getResultSet().isEmpty()) {
            return "";
        }
        List<Map<String, Object>> rows = result.getResultSet();
        if (rows.size() == 1 && rows.get(0).size() == 1) {
            Object value = rows.get(0).values().iterator().next();
            return value == null ? "null" : value.toString().trim();
        }

        List<String> rowStrings = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object value : row.values()) {
                if (!first) {
                    sb.append("|");
                }
                sb.append(value == null ? "null" : value.toString().trim());
                first = false;
            }
            rowStrings.add(sb.toString());
        }
        Collections.sort(rowStrings);
        return String.join("\n", rowStrings);
    }

    private boolean compareRoutineTestCase(
            String actual,
            String expected,
            String matchType,
            String verificationType,
            String printOutputCompareMode) {
        String normalizedActual = actual == null ? "" : actual.trim();
        String normalizedExpected = expected == null ? "" : expected.trim();
        if ("PRINT_OUTPUT".equalsIgnoreCase(verificationType)
                && "LENIENT".equalsIgnoreCase(printOutputCompareMode)) {
            normalizedActual = normalizePrintOutputForCompare(normalizedActual);
            normalizedExpected = normalizePrintOutputForCompare(normalizedExpected);
        }
        if ("PRINT_OUTPUT".equalsIgnoreCase(verificationType)
                && "CONTAINS".equalsIgnoreCase(matchType)) {
            return normalizedActual.toLowerCase(Locale.ROOT)
                    .contains(normalizedExpected.toLowerCase(Locale.ROOT));
        }
        return normalizedActual.equalsIgnoreCase(normalizedExpected);
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

    private String resolveRoutineSql(String sql, String targetSchema, String teacherSchema) {
        if (sql == null) {
            return null;
        }
        String resolved = sql
                .replace("{SCHEMA}", targetSchema)
                .replace("{TEACHER_SCHEMA}", teacherSchema)
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .trim();
        resolved = resolved.replaceAll("(?i)SELECT\\s+return_value\\s+FROM\\s+@(\\w+)", "SELECT @$1 AS return_value");
        return normalizeAiSchemaPlaceholders(resolved, targetSchema, teacherSchema);
    }

    private String normalizeAiSchemaPlaceholders(String sql, String targetSchema, String teacherSchema) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        // [dbo] / dbo. is a recurring AI mistake — REFERENCE SQL of the question
        // may use dbo, but the grading engine runs every batch inside a per-user
        // schema (test_grade_teacher_*, student schema, ...), never dbo. Hardcoded
        // dbo causes "Could not find stored procedure 'dbo.xxx'" at runtime.
        // Coerce to targetSchema. This is safe because no question in this system
        // ever intentionally targets dbo objects.
        return sql
                .replaceAll("(?i)\\[(THIS|THIS_SCHEMA|TARGET_SCHEMA|YOUR_SCHEMA|SCHEMA_NAME)\\]",
                        "[" + targetSchema + "]")
                .replaceAll("(?i)\\b(THIS|THIS_SCHEMA|TARGET_SCHEMA|YOUR_SCHEMA|SCHEMA_NAME)\\s*\\.",
                        "[" + targetSchema + "].")
                .replaceAll("(?i)\\[(TEACHER|TEACHER_SCHEMA)\\]", "[" + teacherSchema + "]")
                .replaceAll("(?i)\\b(TEACHER|TEACHER_SCHEMA)\\s*\\.", "[" + teacherSchema + "].")
                .replaceAll("(?i)\\[dbo\\]\\s*\\.", "[" + targetSchema + "].")
                .replaceAll("(?i)\\bdbo\\s*\\.", "[" + targetSchema + "].");
    }

    private double readTestCaseWeight(JsonNode tc) {
        if (tc.has("score_weight") && tc.get("score_weight").isNumber()) {
            return tc.get("score_weight").asDouble();
        }
        if (tc.has("penalty_value") && tc.get("penalty_value").isNumber()) {
            return tc.get("penalty_value").asDouble();
        }
        return 1;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) {
            return null;
        }
        String value = node.get(field).isTextual() ? node.get(field).asText() : node.get(field).toString();
        return value.isBlank() ? null : value;
    }

    private String textOrDefault(JsonNode node, String field, String defaultValue) {
        String value = textOrNull(node, field);
        return value == null ? defaultValue : value;
    }

    private String truncateForDetail(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 160 ? value : value.substring(0, 160) + "...";
    }

    private record RoutineTestCaseGrade(double earnedPoints, boolean allPassed) {
    }

    private static class RoutineTestCaseExecutionException extends RuntimeException {
        private final String sql;

        RoutineTestCaseExecutionException(String message, String sql, Throwable cause) {
            super(message, cause);
            this.sql = sql;
        }

        String sql() {
            return sql;
        }
    }

    private RubricTestGradeResponse executeTriggerRubricGrading(
            String studentSchema,
            String teacherSchema,
            String gradingRubricJson,
            double totalPoints,
            String ddlScript,
            String correctQuery,
            String studentQuery) {

        List<Map<String, Object>> details = new ArrayList<>();

        try {
            JsonNode rubric = objectMapper.readTree(gradingRubricJson);
            JsonNode gradingPayload = rubric.path("grading_payload");
            JsonNode triggersConfig = gradingPayload.path("triggers");
            JsonNode testCases = gradingPayload.path("test_cases");
            JsonNode gradingSettings = gradingPayload.path("grading_settings");

            boolean positiveOnlyScoring = gradingSettings.path("positive_only_scoring").asBoolean(false);
            boolean caseSensitiveNames = gradingSettings.path("case_sensitive_names").asBoolean(false);

            List<TriggerMetadata> expectedTriggers = examSchemaService
                    .extractTriggerMetadata(teacherSchema);
            List<TriggerMetadata> actualTriggers = examSchemaService
                    .extractTriggerMetadata(studentSchema);

            // Start with full points, then deduct for errors
            double earnedPoints = totalPoints;
            boolean allPassed = true;

            if (expectedTriggers.isEmpty()) {
                details.add(Map.of(
                        "type", "error",
                        "message", "Không tìm thấy trigger trong đáp án chuẩn",
                        "points", 0));
                return RubricTestGradeResponse.of(0, totalPoints, false, details);
            }

            // Build trigger config map for easy lookup
            Map<String, JsonNode> triggerConfigMap = new LinkedHashMap<>();
            if (triggersConfig.isArray()) {
                for (JsonNode tc : triggersConfig) {
                    String expectedName = tc.path("expected_name").asText("");
                    if (!expectedName.isBlank()) {
                        triggerConfigMap.put(expectedName.toLowerCase(), tc);
                    }
                }
            }

            // Check each trigger metadata
            for (TriggerMetadata expected : expectedTriggers) {
                String triggerNameKey = caseSensitiveNames
                        ? expected.getTriggerName()
                        : expected.getTriggerName().toLowerCase();

                JsonNode triggerConfig = triggerConfigMap.get(triggerNameKey);

                // Use rubric config if available, otherwise use default weights
                double existencePoints = triggerConfig != null
                        ? triggerConfig.path("existence_points").asDouble(0.3)
                        : 0.3;
                double tablePoints = triggerConfig != null
                        ? triggerConfig.path("table_points").asDouble(0.2)
                        : 0.2;
                double eventPoints = triggerConfig != null
                        ? triggerConfig.path("event_points").asDouble(0.3)
                        : 0.3;
                double timingPoints = triggerConfig != null
                        ? triggerConfig.path("timing_points").asDouble(0.2)
                        : 0.2;

                TriggerMetadata actual = actualTriggers.stream()
                        .filter(t -> caseSensitiveNames
                                ? t.getTriggerName().equals(expected.getTriggerName())
                                : t.getTriggerName().equalsIgnoreCase(expected.getTriggerName()))
                        .findFirst()
                        .orElse(null);

                // Check existence
                if (actual == null) {
                    details.add(Map.of(
                            "type", "error",
                            "message",
                            String.format("Thiếu Trigger %s", expected.getTriggerName()),
                            "points", -existencePoints));
                    if (!positiveOnlyScoring) {
                        earnedPoints -= existencePoints;
                    }
                    allPassed = false;
                    continue;
                }

                details.add(Map.of(
                        "type", "success",
                        "message", String.format("Trigger %s: tồn tại", expected.getTriggerName()),
                        "points", 0));

                // Check table
                if (expected.getTableName().equalsIgnoreCase(actual.getTableName())) {
                    details.add(Map.of(
                            "type", "success",
                            "message", String.format("Trigger %s: đúng bảng %s",
                                    expected.getTriggerName(), expected.getTableName()),
                            "points", 0));
                } else {
                    details.add(Map.of(
                            "type", "error",
                            "message", String.format("Trigger %s: gắn sai bảng (kỳ vọng: %s, thực tế: %s)",
                                    expected.getTriggerName(), expected.getTableName(), actual.getTableName()),
                            "points", -tablePoints));
                    if (!positiveOnlyScoring) {
                        earnedPoints -= tablePoints;
                    }
                    allPassed = false;
                }

                // Check events
                if (expected.isInsert() == actual.isInsert()
                        && expected.isUpdate() == actual.isUpdate()
                        && expected.isDelete() == actual.isDelete()) {
                    details.add(Map.of(
                            "type", "success",
                            "message", String.format("Trigger %s: đúng sự kiện", expected.getTriggerName()),
                            "points", 0));
                } else {
                    details.add(Map.of(
                            "type", "error",
                            "message",
                            String.format("Trigger %s: sai sự kiện (INSERT/UPDATE/DELETE)", expected.getTriggerName()),
                            "points", -eventPoints));
                    if (!positiveOnlyScoring) {
                        earnedPoints -= eventPoints;
                    }
                    allPassed = false;
                }

                // Check timing
                if (expected.isAfter() == actual.isAfter()) {
                    details.add(Map.of(
                            "type", "success",
                            "message",
                            String.format("Trigger %s: đúng thời điểm chạy", expected.getTriggerName()),
                            "points", 0));
                } else {
                    details.add(Map.of(
                            "type", "error",
                            "message",
                            String.format("Trigger %s: sai thời điểm chạy (AFTER/INSTEAD OF)", expected.getTriggerName()),
                            "points", -timingPoints));
                    if (!positiveOnlyScoring) {
                        earnedPoints -= timingPoints;
                    }
                    allPassed = false;
                }
            }

            // Execute test cases if defined
            if (testCases.isArray() && testCases.size() > 0) {
                for (JsonNode tc : testCases) {
                    String caseId = tc.path("case_id").asText("");
                    String caseName = tc.path("case_name").asText("Unnamed");
                    String setupScript = tc.path("setup_script").asText("");

                    String invocationQuery = tc.path("invocation_query").asText("");
                    String validationQuery = tc.path("validation_query").asText("");
                    String verificationType = tc.path("verification_type").asText("SIDE_EFFECT");

                    // Support both score_weight (new) and penalty_value (old)
                    double scoreWeight = tc.path("score_weight").asDouble(-1);
                    if (scoreWeight < 0) {
                        scoreWeight = tc.path("penalty_value").asDouble(0.2);
                    }

                    if (invocationQuery.isBlank()) {
                        details.add(Map.of(
                                "type", "info",
                                "message",
                                String.format("Test case '%s': bỏ qua (thiếu invocation_query)", caseName),
                                "points", 0));
                        continue;
                    }

                    try {
                        // Reset both schemas before each test case
                        examSchemaService.resetSchema(teacherSchema, false);
                        loadDdlIfPresent(teacherSchema, ddlScript);
                        examSchemaService.resetSchema(studentSchema, false);
                        loadDdlIfPresent(studentSchema, ddlScript);

                        // Re-create triggers
                        if (!correctQuery.isBlank()) {
                            executeSqlScriptBatches(examSchemaService, teacherSchema, correctQuery);
                        }
                        if (!studentQuery.isBlank()) {
                            executeSqlScriptBatches(examSchemaService, studentSchema, studentQuery);
                        }

                        // For EXECUTION_STATUS verification, we only check if invocation succeeds/fails
                        // For SIDE_EFFECT verification, we also need to check validation_query results
                        boolean checkSideEffect = !validationQuery.isBlank() &&
                                "SIDE_EFFECT".equalsIgnoreCase(verificationType);

                        // Build batch SQL that wraps setup + invocation + validation in a single
                        // transaction
                        // This ensures FK constraint state from setup persists during invocation
                        String normalizedSetupTeacher = setupScript.isBlank() ? ""
                                : normalizeDboReferences(setupScript, teacherSchema);
                        String normalizedInvocationTeacher = normalizeDboReferences(invocationQuery, teacherSchema);
                        String normalizedValidationTeacher = checkSideEffect
                                ? normalizeDboReferences(validationQuery, teacherSchema)
                                : "";

                        String normalizedSetupStudent = setupScript.isBlank() ? ""
                                : normalizeDboReferences(setupScript, studentSchema);
                        String normalizedInvocationStudent = normalizeDboReferences(invocationQuery, studentSchema);
                        String normalizedValidationStudent = checkSideEffect
                                ? normalizeDboReferences(validationQuery, studentSchema)
                                : "";

                        // Execute setup + invocation + validation in single transaction on teacher
                        // schema
                        StringBuilder teacherBatch = new StringBuilder();
                        teacherBatch.append("BEGIN TRY\n");
                        teacherBatch.append("  BEGIN TRANSACTION;\n");

                        // Auto-disable FK constraints for ALL tables to avoid false negatives
                        // Trigger logic should be tested independently of FK constraints
                        // Use dynamic SQL to disable FK for all tables in the schema
                        teacherBatch.append("  DECLARE @disableFkSql NVARCHAR(MAX) = '';\n");
                        teacherBatch.append("  SELECT @disableFkSql = @disableFkSql + 'ALTER TABLE [")
                                .append(teacherSchema).append("].[' + t.name + '] NOCHECK CONSTRAINT ALL;'\n");
                        teacherBatch.append("  FROM sys.tables t WHERE t.schema_id = SCHEMA_ID('").append(teacherSchema)
                                .append("');\n");
                        teacherBatch.append("  IF @disableFkSql <> '' EXEC sp_executesql @disableFkSql;\n");

                        if (!normalizedSetupTeacher.isBlank()) {
                            teacherBatch.append("  ").append(normalizedSetupTeacher).append(";\n");
                        }
                        teacherBatch.append("  ").append(normalizedInvocationTeacher).append(";\n");
                        if (checkSideEffect) {
                            teacherBatch.append("  SELECT NULL AS ").append(VALIDATION_MARKER_COLUMN).append(";\n");
                            teacherBatch.append("  ").append(normalizedValidationTeacher).append(";\n");
                        }
                        teacherBatch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
                        teacherBatch.append("END TRY\n");
                        teacherBatch.append("BEGIN CATCH\n");
                        teacherBatch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
                        teacherBatch.append("  THROW;\n");
                        teacherBatch.append("END CATCH;");

                        boolean teacherInvocationFailed = false;
                        String teacherInvocationError = null;
                        SqlExecutionResult teacherResult = null;
                        try {
                            teacherResult = examSchemaService.executeSqlBatchAsSchemaUser(teacherSchema,
                                    teacherBatch.toString());
                        } catch (Exception e) {
                            teacherInvocationFailed = true;
                            teacherInvocationError = e.getMessage();
                        }

                        // Execute setup + invocation + validation in single transaction on student
                        // schema
                        StringBuilder studentBatch = new StringBuilder();
                        studentBatch.append("BEGIN TRY\n");
                        studentBatch.append("  BEGIN TRANSACTION;\n");

                        // Auto-disable FK constraints for ALL tables to avoid false negatives
                        // Use dynamic SQL to disable FK for all tables in the schema
                        studentBatch.append("  DECLARE @disableFkSql NVARCHAR(MAX) = '';\n");
                        studentBatch.append("  SELECT @disableFkSql = @disableFkSql + 'ALTER TABLE [")
                                .append(studentSchema).append("].[' + t.name + '] NOCHECK CONSTRAINT ALL;'\n");
                        studentBatch.append("  FROM sys.tables t WHERE t.schema_id = SCHEMA_ID('").append(studentSchema)
                                .append("');\n");
                        studentBatch.append("  IF @disableFkSql <> '' EXEC sp_executesql @disableFkSql;\n");

                        if (!normalizedSetupStudent.isBlank()) {
                            studentBatch.append("  ").append(normalizedSetupStudent).append(";\n");
                        }
                        studentBatch.append("  ").append(normalizedInvocationStudent).append(";\n");
                        if (checkSideEffect) {
                            studentBatch.append("  SELECT NULL AS ").append(VALIDATION_MARKER_COLUMN).append(";\n");
                            studentBatch.append("  ").append(normalizedValidationStudent).append(";\n");
                        }
                        studentBatch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
                        studentBatch.append("END TRY\n");
                        studentBatch.append("BEGIN CATCH\n");
                        studentBatch.append("  IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\n");
                        studentBatch.append("  THROW;\n");
                        studentBatch.append("END CATCH;");

                        boolean studentInvocationFailed = false;
                        String studentInvocationError = null;
                        SqlExecutionResult studentResult = null;
                        try {
                            studentResult = examSchemaService.executeSqlBatchAsSchemaUser(studentSchema,
                                    studentBatch.toString());
                        } catch (Exception e) {
                            studentInvocationFailed = true;
                            studentInvocationError = e.getMessage();
                        }

                        // Check if both failed with "transaction ended in trigger" (ROLLBACK trigger)
                        boolean isRollbackTrigger = teacherInvocationError != null
                                && teacherInvocationError.toLowerCase().contains("transaction ended in the trigger");
                        boolean studentAlsoRollback = studentInvocationError != null
                                && studentInvocationError.toLowerCase().contains("transaction ended in the trigger");

                        // If validation_query is empty, we only check execution status (for validation
                        // triggers)
                        if (validationQuery.isBlank()) {
                            // For validation triggers (ROLLBACK), check if both succeeded or both failed
                            // the same way
                            if (teacherInvocationFailed == studentInvocationFailed) {
                                if (isRollbackTrigger && studentAlsoRollback) {
                                    details.add(Map.of(
                                            "type", "success",
                                            "message",
                                            String.format(
                                                    "Test case '%s': Đạt (trigger đã từ chối giao dịch đúng như kỳ vọng)",
                                                    caseName),
                                            "points", 0));
                                } else if (!teacherInvocationFailed && !studentInvocationFailed) {
                                    details.add(Map.of(
                                            "type", "success",
                                            "message",
                                            String.format(
                                                    "Test case '%s': Đạt (trigger đã chấp nhận giao dịch đúng như kỳ vọng)",
                                                    caseName),
                                            "points", 0));
                                } else {
                                    details.add(Map.of(
                                            "type", "success",
                                            "message", String.format("Test case '%s': Đạt", caseName),
                                            "points", 0));
                                }
                            } else {
                                details.add(Map.of(
                                        "type", "error",
                                        "message",
                                        String.format("Test case '%s': Không đạt (trạng thái thực thi không khớp)", caseName),
                                        "points", -scoreWeight));
                                if (!positiveOnlyScoring) {
                                    earnedPoints -= scoreWeight;
                                }
                                allPassed = false;
                            }
                            continue;
                        }

                        // For SIDE_EFFECT verification, compare validation query results
                        // Results were already captured in the batch execution above
                        if (checkSideEffect) {
                            // Extract validation results from batch execution (after
                            // VALIDATION_MARKER_COLUMN)
                            SqlExecutionResult teacherFiltered = dropRowsBeforeValidationMarker(teacherResult);
                            SqlExecutionResult studentFiltered = dropRowsBeforeValidationMarker(studentResult);

                            List<Map<String, Object>> expectedRows = teacherFiltered != null
                                    && teacherFiltered.getResultSet() != null
                                            ? teacherFiltered.getResultSet()
                                            : new ArrayList<>();
                            List<Map<String, Object>> actualRows = studentFiltered != null
                                    && studentFiltered.getResultSet() != null
                                            ? studentFiltered.getResultSet()
                                            : new ArrayList<>();

                            // Compare results
                            boolean testPassed = compareQueryResults(actualRows, expectedRows);

                            if (testPassed) {
                                details.add(Map.of(
                                        "type", "success",
                                        "message", String.format("Test case '%s': Đạt", caseName),
                                        "points", 0));
                            } else {
                                details.add(Map.of(
                                        "type", "error",
                                        "message", String.format("Test case '%s': Không đạt (kết quả không khớp)", caseName),
                                        "points", -scoreWeight));
                                if (!positiveOnlyScoring) {
                                    earnedPoints -= scoreWeight;
                                }
                                allPassed = false;
                            }
                        }
                    } catch (Exception e) {
                        details.add(Map.of(
                                "type", "error",
                                "message", String.format("Test case '%s': ERROR - %s", caseName, e.getMessage()),
                                "points", -scoreWeight));
                        if (!positiveOnlyScoring) {
                            earnedPoints -= scoreWeight;
                        }
                        allPassed = false;
                    }
                }
            }

            // Ensure final score is not negative
            double finalScore = Math.max(0, Math.min(earnedPoints, totalPoints));

            return RubricTestGradeResponse.of(finalScore, totalPoints, allPassed, details);

        } catch (Exception e) {
            details.add(Map.of(
                    "type", "error",
                    "message", "Lỗi phân tích rubric: " + e.getMessage(),
                    "points", 0));
            return RubricTestGradeResponse.of(0, totalPoints, false, details);
        }
    }

    private boolean compareQueryResults(List<Map<String, Object>> actual, List<Map<String, Object>> expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }

        if (actual.size() != expected.size()) {
            return false;
        }

        for (int i = 0; i < actual.size(); i++) {
            Map<String, Object> actualRow = actual.get(i);
            Map<String, Object> expectedRow = expected.get(i);

            if (actualRow.size() != expectedRow.size()) {
                return false;
            }

            for (String key : expectedRow.keySet()) {
                Object expectedValue = expectedRow.get(key);
                Object actualValue = actualRow.get(key);

                if (!Objects.equals(normalizeValue(expectedValue), normalizeValue(actualValue))) {
                    return false;
                }
            }
        }

        return true;
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return ((String) value).trim();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).stripTrailingZeros();
        }
        return value;
    }
}
