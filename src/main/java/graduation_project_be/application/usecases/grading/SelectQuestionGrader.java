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
            JsonNode payload = rubric.path("grading_payload");
            JsonNode testCases = payload.path("test_cases");
            if (!testCases.isArray() || testCases.size() == 0) {
                return gradeSelectAcrossDatasets(specification, baseSchemaName, question, studentQuery);
            }

            JsonNode selectRules = resolveSelectGradingRules(question);
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

        if (missingRows > 0 || extraRows > 0) {
            appendSelectIssue(issues, "[" + caseId + "][CARDINAL_MISMATCH] Trả " + actualRowsCount
                    + " dòng, đáp án " + expectedRowsCount + " dòng.");
        } else if (expectedRowsCount > 0) {
            appendSelectIssue(issues, "[" + caseId + "][FULL_MISMATCH] Số dòng đúng (" + expectedRowsCount
                    + ") nhưng giá trị sai.");
        }

        JsonNode rowOrderRule = support.findInsertRule(selectRules, "ROW_ORDER", "OUT_OF_ORDER");
        int rowOrderViolations = 0;
        if (strictOrdering && rowOrderRule != null && !support.hasInsertModifier(rowOrderRule, "SORT_ASC")) {
            rowOrderViolations = countSelectRowOrderViolations(remappedActualRows, safeExpectedRows, comparisonColumns);
            if (rowOrderViolations == 0) {
                rowOrderViolations = 1;
            }
        }

        JsonNode cellNotEqualRule = support.findInsertRule(selectRules, "CELL_VALUE", "NOT_EQUAL");
        JsonNode cellNullRule = support.findInsertRule(selectRules, "CELL_VALUE", "IS_NULL");
        JsonNode cellCompareModifiers = support.firstNonEmptyModifiers(
                support.extractInsertRuleModifiers(cellNotEqualRule),
                support.extractInsertRuleModifiers(cellNullRule));

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

            JsonNode rowOrderRule = support.findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
            int rowOrderViolations = 0;
            if (requireStrictOrder && rowOrderRule != null && !support.hasInsertModifier(rowOrderRule, "SORT_ASC")) {
                rowOrderViolations = countSelectRowOrderViolations(remappedActual, expected, comparisonColumns);
                if (rowOrderViolations == 0) {
                    rowOrderViolations = 1;
                }
            }

            JsonNode cellNotEqualRule = support.findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
            JsonNode cellNullRule = support.findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
            JsonNode cellCompareModifiers = support.firstNonEmptyModifiers(
                    support.extractInsertRuleModifiers(cellNotEqualRule),
                    support.extractInsertRuleModifiers(cellNullRule));

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

            JsonNode rowOrderRule = support.findInsertRule(gradingRules, "ROW_ORDER", "OUT_OF_ORDER");
            int rowOrderViolations = 0;
            if (requireStrictOrder && rowOrderRule != null && !support.hasInsertModifier(rowOrderRule, "SORT_ASC")) {
                rowOrderViolations = countSelectRowOrderViolations(remappedActual, expected, comparisonColumns);
                if (rowOrderViolations == 0) {
                    rowOrderViolations = 1;
                }
            }

            JsonNode cellNotEqualRule = support.findInsertRule(gradingRules, "CELL_VALUE", "NOT_EQUAL");
            JsonNode cellNullRule = support.findInsertRule(gradingRules, "CELL_VALUE", "IS_NULL");
            JsonNode cellCompareModifiers = support.firstNonEmptyModifiers(
                    support.extractInsertRuleModifiers(cellNotEqualRule),
                    support.extractInsertRuleModifiers(cellNullRule));

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
                signature.append(support.normalizeValue(support.getRowValueIgnoreCase(row, column))).append("|||");
            }
            return signature.toString().toLowerCase(Locale.ROOT);
        }

        for (Object value : row.values()) {
            signature.append(support.normalizeValue(value)).append("|||");
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
            Object actualValue = support.getRowValueIgnoreCase(actualRow, column);
            Object expectedValue = support.getRowValueIgnoreCase(expectedRow, column);
            if (support.valuesEqualByMatchTypeWithModifiers(
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
                Object actualValue = support.getRowValueIgnoreCase(rowPair.actualRow(), column);
                Object expectedValue = support.getRowValueIgnoreCase(rowPair.expectedRow(), column);

                boolean equals = support.valuesEqualByMatchTypeWithModifiers(
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
                Object actualValue = support.getRowValueIgnoreCase(rowPair.actualRow(), column);
                Object expectedValue = support.getRowValueIgnoreCase(rowPair.expectedRow(), column);

                String normalizedActual = support.applyInsertModifiers(
                        support.normalizeValueStr(actualValue, false, false),
                        cellModifiers);
                String normalizedExpected = support.applyInsertModifiers(
                        support.normalizeValueStr(expectedValue, false, false),
                        cellModifiers);

                if (!support.isNullLike(normalizedExpected) && support.isNullLike(normalizedActual)) {
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

        JsonNode ruleNode = support.findInsertRule(gradingRules, target, condition);
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
                ? support.readDoubleSetting(ruleNode.path("penalty_value"), -1d)
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

}
