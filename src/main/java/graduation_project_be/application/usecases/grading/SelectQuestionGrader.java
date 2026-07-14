package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SpecDataset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;
import graduation_project_be.application.usecases.GradingTraceCollector;

/** Grades SELECT_QUERY questions (rubric test cases, multi-dataset, rule-based). */
@Slf4j
@RequiredArgsConstructor
public class SelectQuestionGrader {

    private final ExamSchemaService examSchemaService;
    private final ObjectMapper objectMapper;
    private final GradingSupport support;

    public boolean hasSelectRubricTestCases(ExamQuestion question) {
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

    public GradeDecision gradeSelectByRubricTestCases(
            Exam exam,
            ExamSpecification specification,
            List<ExamQuestion> sortedQuestions,
            String baseSchemaName,
            ExamQuestion question,
            String studentQuery) {
        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;

        try {
            JsonNode rubric = objectMapper.readTree(question.getGradingRubric());
            SelectRubricPenaltyNormalizer.normalize(rubric, totalPoints.doubleValue());
            JsonNode payload = rubric.path("grading_payload");
            JsonNode testCases = payload.path("test_cases");
            if (!testCases.isArray() || testCases.size() == 0) {
                return gradeSelectAcrossDatasets(specification, baseSchemaName, question, studentQuery);
            }

            JsonNode selectRules = resolveSelectGradingRules(rubric);
            boolean strictOrdering = support.readBooleanSetting(
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
                String mutationType = tc.path("mutation_type").asText("").trim();
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
                        String traceExpected = null;
                        String traceActual = null;
                        if (!casePassed) {
                            traceExpected = formatRowsForTrace(expected.columns(), expected.rows());
                            traceActual = formatRowsForTrace(null, actualRows);
                        }
                        String configSummary = mutationType.isBlank()
                                ? "SELECT rubric test case"
                                : "SELECT rubric test case | mutation_type=" + mutationType;
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
                                traceExpected, traceActual,
                                configSummary));
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
                support.executeSqlScriptBatches(schemaName, q.getCorrectQuery());
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
            support.executeSqlScriptBatches(schemaName, depQuestion.getCorrectQuery());
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

        List<SelectResultDiff.SelectResultEdit> edits =
                SelectResultDiff.collectColumnEdits(expectedColumns, actualColumns);
        if (edits.isEmpty()) {
            return BigDecimal.ZERO;
        }

        SelectResultScorer.ScoringResult result =
                SelectResultScorer.score(edits, selectRules, maxTotalPoints, support);
        addSelectScoringTrace(
                null,
                "Cấu trúc cột SELECT",
                result,
                maxTotalPoints,
                "SELECT structural rubric");
        for (SelectResultScorer.AppliedEdit applied : result.applied()) {
            if (applied.deduction().compareTo(BigDecimal.ZERO) > 0 || applied.failAllTriggered()) {
                appendSelectIssue(issues, "[Cấu trúc cột] " + describeAppliedEdit(applied));
            }
        }
        return result.totalDeduction().setScale(2, RoundingMode.HALF_UP);
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

        JsonNode cellNotEqualRule = support.findInsertRule(selectRules, "CELL_VALUE", "NOT_EQUAL");
        JsonNode cellNullRule = support.findInsertRule(selectRules, "CELL_VALUE", "IS_NULL");
        JsonNode cellCompareModifiers = support.firstNonEmptyModifiers(
                support.extractInsertRuleModifiers(cellNotEqualRule),
                support.extractInsertRuleModifiers(cellNullRule));

        // SORT_ASC on the ROW_ORDER rule means "accept any row order" -> grade order-insensitive
        // (best-match pairing, no ROW_ORDER penalty).
        JsonNode rowOrderRule = support.findInsertRule(selectRules, "ROW_ORDER", "OUT_OF_ORDER");
        boolean orderSensitive = strictOrdering
                && !(rowOrderRule != null && support.hasInsertModifier(rowOrderRule, "SORT_ASC"));

        List<SelectResultDiff.SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                effectiveExpectedColumns,
                actualColumns,
                safeExpectedRows,
                safeActualRows,
                orderSensitive,
                cellCompareModifiers,
                support);
        if (edits.isEmpty()) {
            return BigDecimal.ZERO;
        }

        int expectedRowsCount = safeExpectedRows.size();
        int actualRowsCount = safeActualRows.size();
        if (expectedRowsCount != actualRowsCount) {
            appendSelectIssue(issues, "[" + caseId + "][CARDINAL_MISMATCH] Trả " + actualRowsCount
                    + " dòng, đáp án " + expectedRowsCount + " dòng.");
        } else if (expectedRowsCount > 0) {
            appendSelectIssue(issues, "[" + caseId + "][FULL_MISMATCH] Số dòng đúng (" + expectedRowsCount
                    + ") nhưng giá trị sai.");
        }

        SelectResultScorer.ScoringResult result =
                SelectResultScorer.score(edits, selectRules, caseMaxPenalty, support);
        StringBuilder caseIssues = new StringBuilder();
        // No per-rule trace items here: the TEST_CASE item emitted by the case loop already
        // carries the rule detail in its message, and a second RUBRIC_RULE line for the same
        // violation reads as a double deduction.
        for (SelectResultScorer.AppliedEdit applied : result.applied()) {
            if (applied.deduction().compareTo(BigDecimal.ZERO) > 0 || applied.failAllTriggered()) {
                appendSelectIssue(caseIssues, describeAppliedEdit(applied));
            }
        }

        BigDecimal rounded = result.totalDeduction().setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.ZERO) > 0) {
            String detail = caseIssues.length() > 0 ? caseIssues.toString().trim() : "kết quả không khớp";
            appendSelectIssue(issues,
                    "[" + caseId + "] " + caseName + ": " + detail
                            + " -> trừ " + rounded.toPlainString() + " điểm.");
        }
        return rounded;
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
            support.executeSqlScriptBatches(schemaName, setupScript);
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
            support.setAllConstraintsEnabled(schemaName, false);
            try {
                try {
                    support.executeSqlScriptBatches(schemaName, setupScript);
                } catch (Exception retryEx) {
                    String relaxedScript = stripRecheckConstraintStatements(setupScript);
                    if (relaxedScript.isBlank() || relaxedScript.equals(setupScript)) {
                        throw retryEx;
                    }
                    clearAllDataInSchema(schemaName);
                    support.setAllConstraintsEnabled(schemaName, false);
                    support.executeSqlScriptBatches(schemaName, relaxedScript);
                }
            } finally {
                try {
                    support.setAllConstraintsEnabled(schemaName, true);
                } catch (Exception recheckEx) {
                    appendSelectIssue(issues,
                            "[" + caseId + "] Ràng buộc FK vẫn không hợp lệ sau setup; tiếp tục chạy với NOCHECK.");
                    try {
                        support.setAllConstraintsEnabled(schemaName, false);
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
        support.setAllConstraintsEnabled(safeSchema, false);
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
            support.setAllConstraintsEnabled(safeSchema, true);
        }
    }

    public GradeDecision gradeSelectAcrossDatasets(
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
                if (support.compareResultSetsStrict(actual, expected, requireStrictOrder)) {
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
            if (support.compareResultSetsStrict(actual, expected, requireStrictOrder)) {
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

            JsonNode cellNotEqualRule = support.findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
            JsonNode cellNullRule = support.findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
            JsonNode cellCompareModifiers = support.firstNonEmptyModifiers(
                    support.extractInsertRuleModifiers(cellNotEqualRule),
                    support.extractInsertRuleModifiers(cellNullRule));

            // SORT_ASC on the ROW_ORDER rule means "accept any row order" -> grade order-insensitive.
            JsonNode rowOrderRule = support.findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
            boolean orderSensitive = requireStrictOrder
                    && !(rowOrderRule != null && support.hasInsertModifier(rowOrderRule, "SORT_ASC"));

            List<SelectResultDiff.SelectResultEdit> edits = SelectResultDiff.collect(
                    expectedColumns, actualColumns, expected, actual, orderSensitive, cellCompareModifiers, support);
            SelectResultScorer.ScoringResult result =
                    SelectResultScorer.score(edits, gradingRules, datasetMaxPoints, support);

            StringBuilder issueBuilder = new StringBuilder();
            addSelectScoringTrace(
                    null,
                    "Kết quả SELECT",
                    result,
                    datasetMaxPoints,
                    "SELECT result rubric");
            for (SelectResultScorer.AppliedEdit applied : result.applied()) {
                if (applied.deduction().compareTo(BigDecimal.ZERO) > 0 || applied.failAllTriggered()) {
                    appendSelectIssue(issueBuilder, describeAppliedEdit(applied));
                }
            }

            BigDecimal earnedPoints = datasetMaxPoints.subtract(result.totalDeduction())
                    .max(BigDecimal.ZERO).min(datasetMaxPoints).setScale(8, RoundingMode.HALF_UP);
            BigDecimal delta = datasetMaxPoints.subtract(earnedPoints).abs();
            boolean allChecksPassed = !result.failAllTriggered() && delta.compareTo(new BigDecimal("0.0001")) <= 0;
            String message = issueBuilder.length() == 0
                    ? "Kết quả SELECT không khớp"
                    : issueBuilder.toString().trim();

            return SelectDatasetDecision.ruleResult(allChecksPassed, result.failAllTriggered(),
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

            if (!support.compareResultSetsStrict(actual, expected, requireStrictOrder)) {
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

            if (support.compareResultSetsStrict(actual, expected, requireStrictOrder)) {
                return SelectDatasetDecision.ruleResult(true, false, null, datasetMaxPoints);
            }

            List<String> expectedColumns = extractSelectColumns(expected);
            List<String> actualColumns = extractSelectColumns(actual);

            JsonNode cellNotEqualRule = support.findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
            JsonNode cellNullRule = support.findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
            JsonNode cellCompareModifiers = support.firstNonEmptyModifiers(
                    support.extractInsertRuleModifiers(cellNotEqualRule),
                    support.extractInsertRuleModifiers(cellNullRule));

            // SORT_ASC on the ROW_ORDER rule means "accept any row order" -> grade order-insensitive.
            JsonNode rowOrderRule = support.findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
            boolean orderSensitive = requireStrictOrder
                    && !(rowOrderRule != null && support.hasInsertModifier(rowOrderRule, "SORT_ASC"));

            List<SelectResultDiff.SelectResultEdit> edits = SelectResultDiff.collect(
                    expectedColumns, actualColumns, expected, actual, orderSensitive, cellCompareModifiers, support);
            SelectResultScorer.ScoringResult result =
                    SelectResultScorer.score(edits, gradingRules, datasetMaxPoints, support);

            StringBuilder issueBuilder = new StringBuilder();
            addSelectScoringTrace(
                    null,
                    datasetLabel,
                    result,
                    datasetMaxPoints,
                    "SELECT dataset rubric: " + datasetLabel);
            for (SelectResultScorer.AppliedEdit applied : result.applied()) {
                if (applied.deduction().compareTo(BigDecimal.ZERO) > 0 || applied.failAllTriggered()) {
                    appendSelectIssue(issueBuilder, describeAppliedEdit(applied));
                }
            }

            BigDecimal earnedPoints = datasetMaxPoints.subtract(result.totalDeduction())
                    .max(BigDecimal.ZERO).min(datasetMaxPoints).setScale(8, RoundingMode.HALF_UP);
            BigDecimal delta = datasetMaxPoints.subtract(earnedPoints).abs();
            boolean allChecksPassed = !result.failAllTriggered() && delta.compareTo(new BigDecimal("0.0001")) <= 0;
            String message = issueBuilder.length() == 0
                    ? "Kết quả SELECT không khớp trên " + datasetLabel
                    : "[" + datasetLabel + "] " + issueBuilder.toString().trim();

            return SelectDatasetDecision.ruleResult(
                    allChecksPassed,
                    result.failAllTriggered(),
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
            BigDecimal points = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
            SelectRubricPenaltyNormalizer.normalize(rubric, points.doubleValue());
            return resolveSelectGradingRules(rubric);
        } catch (Exception e) {
            log.warn("Không thể phân tích grading_rules cho câu SELECT {}: {}", question.getId(), e.getMessage());
        }

        return objectMapper.createArrayNode();
    }

    private JsonNode resolveSelectGradingRules(JsonNode rubric) {
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

    private String describeAppliedEdit(SelectResultScorer.AppliedEdit applied) {
        return SelectResultScorer.describe(applied);
    }

    private void addSelectScoringTrace(
            String caseId,
            String caseName,
            SelectResultScorer.ScoringResult result,
            BigDecimal maxPoints,
            String configSummary) {
        if (!GradingTraceCollector.isActive() || result == null || result.applied().isEmpty()) {
            return;
        }

        BigDecimal safeMaxPoints = maxPoints == null ? BigDecimal.ZERO : maxPoints.max(BigDecimal.ZERO);
        String message = result.applied().stream()
                .map(this::describeAppliedEdit)
                .collect(Collectors.joining(" "));
        BigDecimal deductedPoints = result.totalDeduction().min(safeMaxPoints).max(BigDecimal.ZERO);
        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_RUBRIC_RULE,
                deductedPoints.compareTo(BigDecimal.ZERO) > 0 || result.failAllTriggered()
                        ? GradingTraceItem.STATUS_FAIL
                        : GradingTraceItem.STATUS_WARN,
                "Rule SELECT",
                message,
                caseId,
                caseName,
                null,
                null,
                result.failAllTriggered() ? "FAIL_ALL" : null,
                null,
                null,
                safeMaxPoints,
                deductedPoints.compareTo(BigDecimal.ZERO) > 0 ? deductedPoints : null,
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

    private String formatRowsForTrace(List<String> columns, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "0 dòng";
        List<String> effectiveCols = (columns != null && !columns.isEmpty())
                ? columns
                : new ArrayList<>(rows.get(0).keySet());
        int shown = Math.min(3, rows.size());
        List<String> rowStrs = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            Map<String, Object> row = rows.get(i);
            List<String> vals = effectiveCols.stream()
                    .map(col -> {
                        Object v = row.get(col);
                        return v == null ? "null" : String.valueOf(v);
                    })
                    .collect(Collectors.toList());
            rowStrs.add("[" + String.join(",", vals) + "]");
        }
        String suffix = rows.size() > shown ? "(+" + (rows.size() - shown) + ")" : "";
        return rows.size() + " dòng: " + String.join("|", rowStrs) + suffix;
    }

    private record SelectExpectedRows(List<String> columns, List<Map<String, Object>> rows) {
    }

    private record SelectDatasetSpec(String datasetLabel, String datasetScript) {
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

}
