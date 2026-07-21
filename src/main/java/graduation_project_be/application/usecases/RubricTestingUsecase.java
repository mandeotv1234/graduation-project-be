package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.port.services.SelectQueryStructureAnalyzer;
import graduation_project_be.application.usecases.grading.GradeDecision;
import graduation_project_be.application.usecases.grading.FunctionMetadataContractValidator;
import graduation_project_be.application.usecases.grading.StoredProcedureMetadataGateValidator;
import graduation_project_be.application.usecases.grading.TestCaseWeightNormalizer;
import graduation_project_be.application.usecases.grading.GradingSupport;
import graduation_project_be.application.usecases.grading.InsertDataQuestionGrader;
import graduation_project_be.application.usecases.grading.QueryStructureFacts;
import graduation_project_be.application.usecases.grading.SelectDatasetAdequacyLinter;
import graduation_project_be.application.usecases.grading.SelectQuestionGrader;
import graduation_project_be.application.usecases.grading.SelectResultDiff;
import graduation_project_be.application.usecases.grading.SelectResultScorer;
import graduation_project_be.application.usecases.grading.SelectRubricPenaltyNormalizer;
import graduation_project_be.application.usecases.grading.SelectTrapDiscriminationChecker;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxEngine;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxResult;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxViolation;
import graduation_project_be.application.usecases.request.GenerateGradingRubricRequest;
import graduation_project_be.application.usecases.request.RefineRubricTestCasesRequest;
import graduation_project_be.application.usecases.request.RubricAgentRunRequest;
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
import graduation_project_be.application.usecases.response.RefineRubricTestCasesResponse;
import graduation_project_be.application.usecases.response.RubricAgentRunResponse;
import graduation_project_be.application.usecases.response.RubricTestGradeResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.SqlExecutionResult;
import graduation_project_be.domain.models.TableMetadata;
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

    private final AIService aiService;
    private final ExamSchemaService examSchemaService;
    private final ExamRepository examRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final GetExamQuestionsUsecase getExamQuestionsUsecase;
    private final InsertDataQuestionGrader insertDataGrader;
    private final GradingSupport gradingSupport;
    private final ObjectMapper objectMapper;
    // Reused so the SELECT preview falls back to dataset grading exactly like runtime does.
    private final SelectQuestionGrader selectGrader;
    private final WhiteboxEngine whiteboxEngine;
    // Reused (read-only) to detect whether the correct query aggregates, gating the NULL-presence lint.
    private final SelectQueryStructureAnalyzer queryStructureAnalyzer;
    // Stateless helpers; constructed directly so they stay out of the generated constructor.
    private final SelectTrapDiscriminationChecker trapChecker = new SelectTrapDiscriminationChecker();
    private final SelectDatasetAdequacyLinter adequacyLinter = new SelectDatasetAdequacyLinter();

    public String generateGradingRubric(GenerateGradingRubricRequest request) {
        String sc = request.schemaContext();
        boolean hasForeignKey = sc != null && sc.toUpperCase(java.util.Locale.ROOT).contains("FOREIGN KEY");
        boolean hasReferences = sc != null && sc.toUpperCase(java.util.Locale.ROOT).contains("REFERENCES");
        log.info(
                "[generateGradingRubric] schemaContext length={} containsFOREIGN_KEY={} containsREFERENCES={}\n--- BEGIN schemaContext ---\n{}\n--- END schemaContext ---",
                sc != null ? sc.length() : 0, hasForeignKey, hasReferences, sc);
        String priorQuestionContext = buildPriorQuestionContext(request.contextQueries());

        String rubricJson = aiService.generateGradingRubric(
                request.correctQuery(),
                request.questionContent(),
                request.totalPoints(),
                request.questionType(),
                priorQuestionContext,
                request.schemaContext());

        if ("SELECT_QUERY".equalsIgnoreCase(request.questionType())) {
            rubricJson = SelectRubricPenaltyNormalizer.normalize(
                    rubricJson, request.totalPoints(), objectMapper);
        }

        return rubricJson;
    }

    public RefineRubricTestCasesResponse refineRubricTestCases(RefineRubricTestCasesRequest request) {
        if (request.teacherInstruction() == null || request.teacherInstruction().isBlank()) {
            throw new BadRequestException("Teacher instruction is required");
        }
        if (request.currentRubricJson() == null || request.currentRubricJson().isBlank()) {
            throw new BadRequestException("Current rubric is required");
        }

        String refinedJson = aiService.refineGradingRubricTestCases(
                request.correctQuery(),
                request.questionContent(),
                request.totalPoints(),
                request.questionType(),
                buildPriorQuestionContext(request.contextQueries()),
                request.schemaContext(),
                request.currentRubricJson(),
                request.teacherInstruction(),
                request.targetMode(),
                request.targetTestCaseId());

        if (refinedJson == null || refinedJson.isBlank()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(refinedJson);
            JsonNode rubric = root.has("rubric") ? root.get("rubric") : root;
            if ("SELECT_QUERY".equalsIgnoreCase(request.questionType())) {
                SelectRubricPenaltyNormalizer.normalize(rubric, request.totalPoints());
            }
            return new RefineRubricTestCasesResponse(
                    rubric,
                    readStringArray(root, "changeSummary", "change_summary"),
                    readStringArray(root, "warnings"));
        } catch (Exception e) {
            log.error("Không thể phân tích rubric AI đã chỉnh: {}", e.getMessage(), e);
            return null;
        }
    }

    public RubricAgentRunResponse runRubricQaAgent(RubricAgentRunRequest request) {
        if (request.currentRubricJson() == null || request.currentRubricJson().isBlank()) {
            throw new BadRequestException("Current rubric is required");
        }

        final int maxIterations = 3;
        List<RubricAgentRunResponse.RubricAgentStep> steps = new ArrayList<>();
        List<String> plan = buildRubricAgentPlan(request);
        List<String> changeSummary = new ArrayList<>();
        List<String> fixes = new ArrayList<>();
        List<String> testCaseChanges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        steps.add(agentStep(
                "PLANNING",
                "PASS",
                "Agent lập kế hoạch: " + String.join(" -> ", plan) + ". Tối đa " + maxIterations + " vòng sửa."));

        JsonNode currentRubric;
        try {
            currentRubric = objectMapper.readTree(request.currentRubricJson());
        } catch (Exception e) {
            steps.add(agentStep("VALIDATE_RUBRIC_JSON", "FAIL", "Rubric không phải JSON hợp lệ: " + e.getMessage()));
            throw new BadRequestException("Rubric JSON không hợp lệ: " + e.getMessage());
        }

        JsonNode originalRubric = currentRubric.deepCopy();
        JsonNode finalRubric = currentRubric;
        List<RubricAgentRunResponse.RubricAgentFinding> finalFindings = List.of();
        RuntimeRubricQa lastRuntimeQa = null;
        int completedIterations = 0;
        boolean changed = false;
        boolean patchedInLastIteration = false;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            completedIterations = iteration;
            patchedInLastIteration = false;
            steps.add(agentStep(
                    "AGENT_ITERATION",
                    "PASS",
                    "Bắt đầu vòng agent " + iteration + "/" + maxIterations + "."));

            RubricAgentObservation observation = observeRubricForAgent(
                    currentRubric,
                    request,
                    iteration,
                    steps,
                    changeSummary,
                    warnings);
            currentRubric = observation.rubric();
            finalRubric = currentRubric;
            finalFindings = observation.findings();
            lastRuntimeQa = observation.runtimeQa();
            changed = changed || observation.normalizedChanged();

            if (!hasRepairableFindings(finalFindings)) {
                steps.add(agentStep("READY", "PASS", "Rubric đã qua QA sau vòng " + iteration + "."));
                break;
            }

            try {
                String repairInstruction = buildRubricAgentRepairInstruction(request, finalFindings, lastRuntimeQa);
                String beforePatchJson = objectMapper.writeValueAsString(currentRubric);
                RefineRubricTestCasesResponse repaired = refineRubricTestCases(new RefineRubricTestCasesRequest(
                        request.correctQuery(),
                        request.questionContent(),
                        request.totalPoints(),
                        request.questionType(),
                        request.schemaContext(),
                        request.contextQueries(),
                        beforePatchJson,
                        repairInstruction,
                        "IMPROVE_COVERAGE",
                        ""));

                if (repaired == null || repaired.rubric() == null || repaired.rubric().isMissingNode()) {
                    steps.add(agentStep("PATCH_RUBRIC", "FAIL",
                            "Vòng " + iteration + ": AI không trả về rubric đã sửa."));
                    warnings.add("AI repair không trả về rubric hợp lệ; giữ rubric hiện tại.");
                    break;
                } else {
                    JsonNode repairedRubric = normalizeRubricEnvelopeForAgent(
                            repaired.rubric(),
                            request,
                            changeSummary);
                    List<String> caseChanges = describeRubricCaseChanges(currentRubric, repairedRubric, request);
                    testCaseChanges.addAll(caseChanges);
                    fixes.addAll(repaired.changeSummary());
                    changed = true;
                    patchedInLastIteration = true;
                    currentRubric = repairedRubric;
                    finalRubric = currentRubric;
                    changeSummary.addAll(repaired.changeSummary());
                    warnings.addAll(repaired.warnings());
                    steps.add(agentStep("PATCH_RUBRIC", "PASS",
                            "Vòng " + iteration + ": AI đã patch rubric. Agent sẽ chạy lại tool để verify."));
                }
            } catch (Exception e) {
                log.warn("Rubric QA auto-repair failed: {}", e.getMessage(), e);
                steps.add(agentStep("PATCH_RUBRIC", "FAIL",
                        "Vòng " + iteration + ": không thể tự sửa rubric: " + e.getMessage()));
                warnings.add("Không thể tự sửa rubric: " + e.getMessage());
                break;
            }

            if (iteration == maxIterations && patchedInLastIteration) {
                steps.add(agentStep(
                        "FINAL_VERIFY",
                        "WARN",
                        "Đã dùng hết " + maxIterations + " vòng patch; chạy verify cuối không patch thêm."));
                RubricAgentObservation finalObservation = observeRubricForAgent(
                        currentRubric,
                        request,
                        iteration + 1,
                        steps,
                        changeSummary,
                        warnings);
                finalRubric = finalObservation.rubric();
                finalFindings = finalObservation.findings();
                lastRuntimeQa = finalObservation.runtimeQa();
                changed = changed || finalObservation.normalizedChanged();
            }
        }

        if (changeSummary.isEmpty() && changed) {
            changeSummary.add("Đã chuẩn hóa envelope rubric để đúng schema chấm.");
        }
        if (fixes.isEmpty() && changed && !originalRubric.equals(finalRubric)) {
            fixes.add("Agent đã cập nhật rubric sau khi chạy QA tool.");
        }

        List<String> finalWarnings = new ArrayList<>(warnings);
        finalFindings.stream()
                .filter(finding -> "WARNING".equalsIgnoreCase(finding.severity()))
                .map(RubricAgentRunResponse.RubricAgentFinding::message)
                .forEach(finalWarnings::add);

        return new RubricAgentRunResponse(
                finalRubric,
                steps,
                finalFindings,
                distinctStrings(plan),
                distinctStrings(changeSummary),
                distinctStrings(fixes),
                distinctStrings(testCaseChanges),
                distinctStrings(finalWarnings),
                completedIterations,
                changed,
                calculateRubricAgentConfidence(steps, finalFindings));
    }

    private RubricAgentObservation observeRubricForAgent(
            JsonNode rubric,
            RubricAgentRunRequest request,
            int iteration,
            List<RubricAgentRunResponse.RubricAgentStep> steps,
            List<String> changeSummary,
            List<String> warnings) {
        JsonNode parsedRubric;
        try {
            parsedRubric = objectMapper.readTree(objectMapper.writeValueAsString(rubric));
            steps.add(agentStep("VALIDATE_RUBRIC_JSON", "PASS",
                    "Vòng " + iteration + ": rubric là JSON hợp lệ."));
        } catch (Exception e) {
            List<RubricAgentRunResponse.RubricAgentFinding> findings = List.of(agentFinding(
                    "ERROR",
                    "RUBRIC_JSON_INVALID",
                    "Vòng " + iteration + ": rubric không phải JSON hợp lệ: " + e.getMessage()));
            steps.add(agentStep("VALIDATE_RUBRIC_JSON", "FAIL",
                    "Vòng " + iteration + ": rubric không phải JSON hợp lệ."));
            return new RubricAgentObservation(rubric, findings, null, false);
        }

        JsonNode normalizedRubric = normalizeRubricEnvelopeForAgent(parsedRubric, request, changeSummary);
        boolean normalizedChanged = !parsedRubric.equals(normalizedRubric);

        DerivedExpectedValuesQa derived = deriveExpectedValuesForAgent(normalizedRubric, request);
        steps.add(derived.step());
        warnings.addAll(derived.warnings());

        RuntimeRubricQa runtimeQa = runReferenceGradeForAgent(normalizedRubric, request);
        steps.add(runtimeQa.step());
        warnings.addAll(runtimeQa.warnings());

        List<RubricAgentRunResponse.RubricAgentFinding> coverageFindings =
                analyzeRubricForAgent(normalizedRubric, request);
        steps.add(summarizeFindingsStep("ANALYZE_MUTATION_COVERAGE", coverageFindings));

        List<RubricAgentRunResponse.RubricAgentFinding> findings = new ArrayList<>();
        findings.addAll(derived.findings());
        findings.addAll(runtimeQa.findings());
        findings.addAll(coverageFindings);
        findings = compactAgentFindings(findings);

        return new RubricAgentObservation(normalizedRubric, findings, runtimeQa, normalizedChanged);
    }

    private List<String> buildRubricAgentPlan(RubricAgentRunRequest request) {
        List<String> plan = new ArrayList<>();
        plan.add("VALIDATE_RUBRIC_JSON");
        plan.add("DERIVE_EXPECTED_VALUES");
        plan.add("RUN_TEST_GRADE");
        plan.add("ANALYZE_MUTATION_COVERAGE");
        plan.add("PATCH_RUBRIC khi có issue");
        plan.add("VERIFY lại tối đa 3 vòng");
        if (request.teacherInstruction() != null && !request.teacherInstruction().isBlank()) {
            plan.add("Ưu tiên ghi chú giáo viên: " + request.teacherInstruction().trim());
        }
        return plan;
    }

    private boolean hasRepairableFindings(List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        return findings != null && findings.stream()
                .anyMatch(finding -> "ERROR".equalsIgnoreCase(finding.severity())
                        || "WARNING".equalsIgnoreCase(finding.severity()));
    }

    private DerivedExpectedValuesQa deriveExpectedValuesForAgent(JsonNode rubric, RubricAgentRunRequest request) {
        List<RubricAgentRunResponse.RubricAgentFinding> findings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String questionType = normalizeAgentQuestionType(request.questionType());
        JsonNode payload = resolveAgentPayload(rubric);

        switch (questionType) {
            case "CREATE_TABLE" -> deriveCreateTableExpectedValues(payload, request, findings);
            case "INSERT_DATA" -> deriveInsertDataExpectedValues(payload, request, findings);
            case "SELECT_QUERY" -> deriveSelectExpectedValues(payload, request, findings, warnings);
            case "FUNCTION", "STORED_PROCEDURE", "PROCEDURE", "ROUTINE" ->
                    deriveRoutineExpectedValues(payload, findings);
            case "TRIGGER" -> deriveTriggerExpectedValues(payload, findings);
            default -> warnings.add("DERIVE_EXPECTED_VALUES chưa có rule chuyên biệt cho " + questionType + ".");
        }

        String status;
        String message;
        long errors = findings.stream().filter(f -> "ERROR".equalsIgnoreCase(f.severity())).count();
        long warnCount = findings.stream().filter(f -> "WARNING".equalsIgnoreCase(f.severity())).count();
        if (errors > 0) {
            status = "FAIL";
            message = "Không derive được expected values đầy đủ: " + errors + " lỗi, " + warnCount + " cảnh báo.";
        } else if (warnCount > 0) {
            status = "WARN";
            message = "Derive expected values có " + warnCount + " cảnh báo cần sửa hoặc rà soát.";
        } else {
            status = "PASS";
            message = "Đã derive/đối chiếu expected values từ rubric và đáp án chuẩn.";
        }

        return new DerivedExpectedValuesQa(
                agentStep("DERIVE_EXPECTED_VALUES", status, message),
                findings,
                warnings);
    }

    private void deriveCreateTableExpectedValues(
            JsonNode payload,
            RubricAgentRunRequest request,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        Set<String> expectedTablesFromSql = extractCreatedTableNames(request.correctQuery());
        JsonNode rubricTables = firstArray(payload.path("tables"), payload.path("expected_tables"));
        Set<String> rubricTablesByName = new LinkedHashSet<>();
        if (rubricTables.isArray()) {
            rubricTables.forEach(table -> {
                String name = firstText(table, "expected_name", "table_name", "name");
                if (!name.isBlank()) {
                    rubricTablesByName.add(name.toLowerCase(Locale.ROOT));
                }
            });
        }

        if (!expectedTablesFromSql.isEmpty()) {
            for (String tableName : expectedTablesFromSql) {
                if (!rubricTablesByName.contains(tableName.toLowerCase(Locale.ROOT))) {
                    findings.add(agentFinding("ERROR", "DERIVE_CREATE_TABLE_MISSING",
                            "Đáp án tạo bảng " + tableName + " nhưng rubric chưa có bảng tương ứng."));
                }
            }
        }
    }

    private void deriveInsertDataExpectedValues(
            JsonNode payload,
            RubricAgentRunRequest request,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        Set<String> insertedTables = extractInsertedTableNames(request.correctQuery());
        JsonNode rubricTables = payload.path("tables");
        Set<String> rubricTablesByName = new LinkedHashSet<>();
        if (rubricTables.isArray()) {
            rubricTables.forEach(table -> {
                String name = firstText(table, "table_name", "expected_name", "name");
                if (!name.isBlank()) {
                    rubricTablesByName.add(name.toLowerCase(Locale.ROOT));
                }
            });
        }

        for (String tableName : insertedTables) {
            if (!rubricTablesByName.contains(tableName.toLowerCase(Locale.ROOT))) {
                findings.add(agentFinding("WARNING", "DERIVE_INSERT_TABLE_MISSING",
                        "Đáp án INSERT vào " + tableName + " nhưng rubric chưa có expected_data cho bảng này."));
            }
        }
    }

    private void deriveSelectExpectedValues(
            JsonNode payload,
            RubricAgentRunRequest request,
            List<RubricAgentRunResponse.RubricAgentFinding> findings,
            List<String> warnings) {
        JsonNode testCases = payload.path("test_cases");
        if (!testCases.isArray() || testCases.isEmpty()) {
            warnings.add("SELECT không có test_cases[] nên expected values sẽ fallback theo đáp án mẫu/runtime.");
            return;
        }
        if (request.correctQuery() == null || request.correctQuery().isBlank()) {
            findings.add(agentFinding("ERROR", "DERIVE_SELECT_CORRECT_QUERY_MISSING",
                    "Không thể derive expected_result SELECT vì thiếu correctQuery."));
            return;
        }
        for (JsonNode testCase : testCases) {
            String caseId = testCase.path("case_id").asText("");
            String setup = testCase.path("setup_custom_script").asText("");
            JsonNode expectedResult = testCase.path("expected_result");
            if (setup.isBlank()) {
                findings.add(agentFinding("WARNING", "DERIVE_SELECT_SETUP_MISSING",
                        "Test case " + displayName(caseId) + " thiếu setup_custom_script để derive expected_result ổn định."));
            }
            if (expectedResult.isMissingNode() || expectedResult.isNull()) {
                warnings.add("Test case " + displayName(caseId)
                        + " chưa lưu expected_result; runtime sẽ derive bằng correctQuery khi chấm thử.");
            }
        }
    }

    private void deriveRoutineExpectedValues(
            JsonNode payload,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        JsonNode testCases = payload.path("test_cases");
        if (!testCases.isArray() || testCases.isEmpty()) {
            findings.add(agentFinding("ERROR", "DERIVE_ROUTINE_CASES_MISSING",
                    "Không có test_cases[] để derive expected output cho routine."));
            return;
        }
        for (JsonNode testCase : testCases) {
            String caseId = testCase.path("case_id").asText("");
            if (testCase.path("validation_query").asText("").isBlank()
                    && testCase.path("invocation_query").asText("").isBlank()) {
                findings.add(agentFinding("ERROR", "DERIVE_ROUTINE_VALIDATION_MISSING",
                        "Test case " + displayName(caseId) + " thiếu validation_query/invocation_query."));
            }
        }
    }

    private void deriveTriggerExpectedValues(
            JsonNode payload,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        JsonNode testCases = payload.path("test_cases");
        if (!testCases.isArray() || testCases.isEmpty()) {
            findings.add(agentFinding("ERROR", "DERIVE_TRIGGER_CASES_MISSING",
                    "Không có test_cases[] để derive side-effect expected cho trigger."));
            return;
        }
        for (JsonNode testCase : testCases) {
            String caseId = testCase.path("case_id").asText("");
            if (testCase.path("invocation_query").asText("").isBlank()) {
                findings.add(agentFinding("ERROR", "DERIVE_TRIGGER_INVOCATION_MISSING",
                        "Test case " + displayName(caseId) + " thiếu invocation_query."));
            }
            if (testCase.path("validation_query").asText("").isBlank()) {
                findings.add(agentFinding("WARNING", "DERIVE_TRIGGER_VALIDATION_MISSING",
                        "Test case " + displayName(caseId) + " thiếu validation_query để xác minh side effect."));
            }
        }
    }

    private List<String> describeRubricCaseChanges(
            JsonNode before,
            JsonNode after,
            RubricAgentRunRequest request) {
        List<String> changes = new ArrayList<>();
        String questionType = normalizeAgentQuestionType(request.questionType());
        JsonNode beforePayload = resolveAgentPayload(before);
        JsonNode afterPayload = resolveAgentPayload(after);

        int beforeCaseCount = countArray(beforePayload.path("test_cases"));
        int afterCaseCount = countArray(afterPayload.path("test_cases"));
        if (beforeCaseCount != afterCaseCount) {
            changes.add("Số test case đổi từ " + beforeCaseCount + " thành " + afterCaseCount + ".");
        }

        int beforeTableCount = countArray(firstArray(beforePayload.path("tables"), before.path("tables")));
        int afterTableCount = countArray(firstArray(afterPayload.path("tables"), after.path("tables")));
        if (beforeTableCount != afterTableCount) {
            changes.add("Số bảng/dataset rubric đổi từ " + beforeTableCount + " thành " + afterTableCount + ".");
        }

        int beforeRuleCount = countArray(firstArray(beforePayload.path("grading_rules"), before.path("grading_rules")));
        int afterRuleCount = countArray(firstArray(afterPayload.path("grading_rules"), after.path("grading_rules")));
        if (beforeRuleCount != afterRuleCount) {
            changes.add("Số grading rule đổi từ " + beforeRuleCount + " thành " + afterRuleCount + ".");
        }

        if (("FUNCTION".equals(questionType) || "STORED_PROCEDURE".equals(questionType) || "TRIGGER".equals(questionType))
                && beforeCaseCount == afterCaseCount
                && !beforePayload.path("test_cases").equals(afterPayload.path("test_cases"))) {
            changes.add("Nội dung test_cases đã được chỉnh nhưng số lượng không đổi.");
        }
        if (changes.isEmpty() && !before.equals(after)) {
            changes.add("Rubric JSON đã được chỉnh nhưng không phát hiện thay đổi số lượng testcase/rule.");
        }
        return changes;
    }

    private int countArray(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    private List<RubricAgentRunResponse.RubricAgentFinding> compactAgentFindings(
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }

        Map<String, RubricAgentRunResponse.RubricAgentFinding> compacted = new LinkedHashMap<>();
        for (RubricAgentRunResponse.RubricAgentFinding finding : findings) {
            if (finding == null) {
                continue;
            }
            String key = finding.severity() + "|" + finding.code() + "|" + finding.message();
            compacted.putIfAbsent(key, finding);
        }
        return new ArrayList<>(compacted.values());
    }

    private JsonNode normalizeRubricEnvelopeForAgent(
            JsonNode rubric,
            RubricAgentRunRequest request,
            List<String> changeSummary) {
        if (rubric == null || !rubric.isObject()) {
            throw new BadRequestException("Rubric phải là JSON object.");
        }

        String questionType = normalizeAgentQuestionType(request.questionType());
        ObjectNode root = ((ObjectNode) rubric).deepCopy();
        boolean changed = false;

        if (!root.hasNonNull("question_category") || root.path("question_category").asText("").isBlank()) {
            root.put("question_category", questionType);
            changed = true;
        }
        if (!root.hasNonNull("total_points") || !root.path("total_points").isNumber()) {
            root.put("total_points", request.totalPoints());
            changed = true;
        }

        JsonNode payload = root.path("grading_payload");
        if (!payload.isObject()) {
            ObjectNode wrappedPayload = root.deepCopy();
            wrappedPayload.remove("question_id");
            wrappedPayload.remove("question_category");
            wrappedPayload.remove("total_points");

            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("question_category", questionType);
            wrapper.put("total_points", request.totalPoints());
            wrapper.set("grading_payload", wrappedPayload);
            root = wrapper;
            changed = true;
        }

        if (changed) {
            changeSummary.add("Đã chuẩn hóa rubric về dạng có question_category, total_points và grading_payload.");
        }
        return root;
    }

    private List<RubricAgentRunResponse.RubricAgentFinding> analyzeRubricForAgent(
            JsonNode rubric,
            RubricAgentRunRequest request) {
        List<RubricAgentRunResponse.RubricAgentFinding> findings = new ArrayList<>();
        String expectedType = normalizeAgentQuestionType(request.questionType());

        String actualType = rubric.path("question_category").asText("");
        if (!expectedType.equalsIgnoreCase(actualType)) {
            findings.add(agentFinding(
                    "WARNING",
                    "CATEGORY_MISMATCH",
                    "question_category hiện là " + actualType + " nhưng câu hỏi đang là " + expectedType + "."));
        }

        double rubricPoints = rubric.path("total_points").asDouble(Double.NaN);
        if (!Double.isFinite(rubricPoints)) {
            findings.add(agentFinding("ERROR", "TOTAL_POINTS_MISSING", "Rubric thiếu total_points dạng số."));
        } else if (Math.abs(rubricPoints - request.totalPoints()) > 0.01) {
            findings.add(agentFinding(
                    "WARNING",
                    "TOTAL_POINTS_MISMATCH",
                    "total_points trong rubric lệch với điểm câu hỏi (" + rubricPoints + " / "
                            + request.totalPoints() + ")."));
        }

        JsonNode payload = resolveAgentPayload(rubric);
        if (payload == null || !payload.isObject()) {
            findings.add(agentFinding("ERROR", "PAYLOAD_MISSING", "Rubric thiếu grading_payload object."));
            return findings;
        }

        switch (expectedType) {
            case "CREATE_TABLE" -> analyzeCreateTableRubric(payload, request, findings);
            case "INSERT_DATA" -> analyzeInsertDataRubric(payload, rubric, findings);
            case "SELECT_QUERY" -> analyzeSelectRubric(payload, rubric, findings);
            case "FUNCTION", "STORED_PROCEDURE", "PROCEDURE", "ROUTINE" ->
                    analyzeRoutineRubric(payload, findings);
            case "TRIGGER" -> analyzeTriggerRubric(payload, findings);
            default -> findings.add(agentFinding(
                    "WARNING",
                    "UNKNOWN_QUESTION_TYPE",
                    "Agent chưa có rule QA chuyên biệt cho loại câu hỏi " + expectedType + "."));
        }

        if (findings.isEmpty()) {
            findings.add(agentFinding("INFO", "QA_PASS", "Rubric đạt các kiểm tra cấu trúc cơ bản."));
        }
        return findings;
    }

    private void analyzeCreateTableRubric(
            JsonNode payload,
            RubricAgentRunRequest request,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        JsonNode tables = firstArray(payload.path("tables"), payload.path("expected_tables"));
        if (tables.isMissingNode() || !tables.isArray() || tables.isEmpty()) {
            findings.add(agentFinding("ERROR", "CREATE_TABLE_EMPTY_TABLES",
                    "CREATE_TABLE rubric chưa có danh sách bảng kỳ vọng."));
            return;
        }

        boolean hasPrimaryKey = false;
        boolean hasForeignKey = false;
        for (JsonNode table : tables) {
            String tableName = firstText(table, "expected_name", "table_name", "name");
            if (tableName.isBlank()) {
                findings.add(agentFinding("ERROR", "CREATE_TABLE_NAME_MISSING",
                        "Có bảng trong rubric chưa có expected_name."));
            }
            JsonNode columns = table.path("columns");
            if (!columns.isArray() || columns.isEmpty()) {
                findings.add(agentFinding("ERROR", "CREATE_TABLE_COLUMNS_MISSING",
                        "Bảng " + displayName(tableName) + " chưa có columns[]."));
            } else {
                for (JsonNode column : columns) {
                    if (column.path("name").asText("").isBlank()) {
                        findings.add(agentFinding("ERROR", "CREATE_TABLE_COLUMN_NAME_MISSING",
                                "Bảng " + displayName(tableName) + " có cột thiếu name."));
                    }
                    if (column.path("expected_type").asText("").isBlank()) {
                        findings.add(agentFinding("WARNING", "CREATE_TABLE_COLUMN_TYPE_MISSING",
                                "Cột " + displayName(column.path("name").asText("")) + " thiếu expected_type."));
                    }
                }
            }
            JsonNode constraints = table.path("constraints");
            if (constraints.isArray()) {
                for (JsonNode constraint : constraints) {
                    String type = constraint.path("type").asText("");
                    hasPrimaryKey = hasPrimaryKey || "PRIMARY_KEY".equalsIgnoreCase(type);
                    hasForeignKey = hasForeignKey || "FOREIGN_KEY".equalsIgnoreCase(type);
                }
            }
        }

        String correctSql = request.correctQuery() == null ? "" : request.correctQuery().toUpperCase(Locale.ROOT);
        if (correctSql.contains("PRIMARY KEY") && !hasPrimaryKey) {
            findings.add(agentFinding("WARNING", "CREATE_TABLE_PK_NOT_COVERED",
                    "SQL đáp án có PRIMARY KEY nhưng rubric chưa thấy constraint PRIMARY_KEY."));
        }
        if ((correctSql.contains("FOREIGN KEY") || correctSql.contains("REFERENCES")) && !hasForeignKey) {
            findings.add(agentFinding("WARNING", "CREATE_TABLE_FK_NOT_COVERED",
                    "SQL đáp án có FOREIGN KEY/REFERENCES nhưng rubric chưa thấy constraint FOREIGN_KEY."));
        }
    }

    private void analyzeInsertDataRubric(
            JsonNode payload,
            JsonNode rubric,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        JsonNode tables = firstArray(payload.path("tables"), rubric.path("tables"));
        JsonNode gradingRules = firstArray(payload.path("grading_rules"), rubric.path("grading_rules"));

        if ((tables.isMissingNode() || !tables.isArray() || tables.isEmpty())
                && (gradingRules.isMissingNode() || !gradingRules.isArray() || gradingRules.isEmpty())) {
            findings.add(agentFinding("ERROR", "INSERT_DATA_EMPTY_RUBRIC",
                    "INSERT_DATA rubric cần có tables[] hoặc grading_rules[]."));
            return;
        }

        if (gradingRules.isArray()) {
            for (JsonNode rule : gradingRules) {
                if (rule.path("rule_name").asText("").isBlank()) {
                    findings.add(agentFinding("WARNING", "INSERT_DATA_RULE_NAME_MISSING",
                            "Có grading rule thiếu rule_name."));
                }
            }
        }

        if (tables.isArray()) {
            for (JsonNode table : tables) {
                String tableName = firstText(table, "table_name", "expected_name", "name");
                if (tableName.isBlank()) {
                    findings.add(agentFinding("ERROR", "INSERT_DATA_TABLE_NAME_MISSING",
                            "Có bảng INSERT_DATA thiếu table_name."));
                }
                JsonNode expectedData = table.path("expected_data");
                if (!expectedData.isArray() || expectedData.isEmpty()) {
                    findings.add(agentFinding("WARNING", "INSERT_DATA_EXPECTED_DATA_MISSING",
                            "Bảng " + displayName(tableName) + " chưa có expected_data[]."));
                }
                JsonNode columnsConfig = table.path("columns_config");
                if (!columnsConfig.isArray() || columnsConfig.isEmpty()) {
                    findings.add(agentFinding("WARNING", "INSERT_DATA_COLUMNS_CONFIG_MISSING",
                            "Bảng " + displayName(tableName) + " chưa có columns_config[]."));
                }
            }
        }
    }

    private void analyzeSelectRubric(
            JsonNode payload,
            JsonNode rubric,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        JsonNode testCases = firstArray(payload.path("test_cases"), rubric.path("test_cases"));
        JsonNode gradingRules = firstArray(payload.path("grading_rules"), rubric.path("grading_rules"));

        if (gradingRules.isMissingNode() || !gradingRules.isArray() || gradingRules.isEmpty()) {
            findings.add(agentFinding("WARNING", "SELECT_RULES_MISSING",
                    "SELECT rubric chưa có grading_rules[] để trừ điểm chi tiết."));
        }
        if (testCases.isMissingNode() || !testCases.isArray() || testCases.isEmpty()) {
            findings.add(agentFinding("WARNING", "SELECT_TEST_CASES_MISSING",
                    "SELECT rubric chưa có test_cases[]; runtime sẽ fallback so sánh đáp án mẫu."));
            return;
        }
        if (testCases.size() < 3) {
            findings.add(agentFinding("WARNING", "SELECT_LOW_COVERAGE",
                    "SELECT rubric chỉ có " + testCases.size() + " test case, nên có ít nhất 3 case để bao phủ bẫy."));
        }
        for (JsonNode testCase : testCases) {
            String caseId = testCase.path("case_id").asText("");
            if (caseId.isBlank()) {
                findings.add(agentFinding("ERROR", "SELECT_CASE_ID_MISSING",
                        "Có SELECT test case thiếu case_id."));
            }
            if (testCase.path("mutation_type").asText("").isBlank()) {
                findings.add(agentFinding("WARNING", "SELECT_MUTATION_TYPE_MISSING",
                        "Test case " + displayName(caseId) + " thiếu mutation_type."));
            }
            if (testCase.path("setup_custom_script").asText("").isBlank()) {
                findings.add(agentFinding("WARNING", "SELECT_SETUP_SCRIPT_MISSING",
                        "Test case " + displayName(caseId) + " thiếu setup_custom_script."));
            }
        }
    }

    private void analyzeRoutineRubric(
            JsonNode payload,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        JsonNode routines = payload.path("routines");
        if (!routines.isArray() || routines.isEmpty()) {
            findings.add(agentFinding("ERROR", "ROUTINE_METADATA_MISSING",
                    "Rubric FUNCTION/STORED_PROCEDURE thiếu routines[]."));
        } else {
            for (JsonNode routine : routines) {
                if (routine.path("expected_name").asText("").isBlank()) {
                    findings.add(agentFinding("ERROR", "ROUTINE_NAME_MISSING",
                            "Có routine thiếu expected_name."));
                }
                if (routine.path("expected_type").asText("").isBlank()) {
                    findings.add(agentFinding("WARNING", "ROUTINE_TYPE_MISSING",
                            "Routine " + displayName(routine.path("expected_name").asText(""))
                                    + " thiếu expected_type."));
                }
            }
        }

        JsonNode testCases = payload.path("test_cases");
        analyzeWeightedTestCases(testCases, "ROUTINE", findings);
    }

    private void analyzeTriggerRubric(
            JsonNode payload,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        JsonNode triggers = payload.path("triggers");
        if (!triggers.isArray() || triggers.isEmpty()) {
            findings.add(agentFinding("ERROR", "TRIGGER_METADATA_MISSING", "TRIGGER rubric thiếu triggers[]."));
        } else {
            for (JsonNode trigger : triggers) {
                String name = trigger.path("expected_name").asText("");
                if (name.isBlank()) {
                    findings.add(agentFinding("ERROR", "TRIGGER_NAME_MISSING",
                            "Có trigger thiếu expected_name."));
                }
                if (trigger.path("expected_table_name").asText("").isBlank()) {
                    findings.add(agentFinding("WARNING", "TRIGGER_TABLE_MISSING",
                            "Trigger " + displayName(name) + " thiếu expected_table_name."));
                }
                boolean hasEvent = trigger.path("is_insert").asBoolean(false)
                        || trigger.path("is_update").asBoolean(false)
                        || trigger.path("is_delete").asBoolean(false);
                if (!hasEvent) {
                    findings.add(agentFinding("ERROR", "TRIGGER_EVENT_MISSING",
                            "Trigger " + displayName(name) + " chưa bật INSERT/UPDATE/DELETE."));
                }
            }
        }

        JsonNode testCases = payload.path("test_cases");
        analyzeWeightedTestCases(testCases, "TRIGGER", findings);
    }

    private void analyzeWeightedTestCases(
            JsonNode testCases,
            String prefix,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        if (!testCases.isArray() || testCases.isEmpty()) {
            findings.add(agentFinding("ERROR", prefix + "_TEST_CASES_MISSING",
                    prefix + " rubric thiếu test_cases[]."));
            return;
        }

        double weightSum = 0.0;
        for (JsonNode testCase : testCases) {
            String caseId = testCase.path("case_id").asText("");
            if (caseId.isBlank()) {
                findings.add(agentFinding("ERROR", prefix + "_CASE_ID_MISSING",
                        "Có " + prefix + " test case thiếu case_id."));
            }
            if (testCase.path("setup_script").asText("").isBlank()) {
                findings.add(agentFinding("WARNING", prefix + "_SETUP_SCRIPT_MISSING",
                        "Test case " + displayName(caseId) + " thiếu setup_script."));
            }
            if (testCase.path("validation_query").asText("").isBlank()) {
                findings.add(agentFinding("WARNING", prefix + "_VALIDATION_QUERY_MISSING",
                        "Test case " + displayName(caseId) + " thiếu validation_query."));
            }
            weightSum += testCase.path("score_weight").asDouble(0.0);
        }
        if (Math.abs(weightSum - 1.0) > 0.05) {
            findings.add(agentFinding("WARNING", prefix + "_WEIGHT_SUM_MISMATCH",
                    "Tổng score_weight hiện là " + roundForMessage(weightSum) + ", nên xấp xỉ 1.0."));
        }
    }

    private RuntimeRubricQa runReferenceGradeForAgent(JsonNode rubric, RubricAgentRunRequest request) {
        List<RubricAgentRunResponse.RubricAgentFinding> findings = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String correctQuery = request.correctQuery() == null ? "" : request.correctQuery().trim();
        String questionType = normalizeAgentQuestionType(request.questionType());

        if (correctQuery.isBlank()) {
            String message = "Thiếu SQL đáp án nên bỏ qua chấm thử bằng đáp án chuẩn.";
            warnings.add(message);
            return new RuntimeRubricQa(
                    agentStep("RUN_TEST_GRADE", "SKIPPED", message),
                    findings,
                    warnings);
        }

        if (!"CREATE_TABLE".equals(questionType) && request.examId() == null) {
            String message = "Thiếu examId nên bỏ qua chấm thử runtime cho " + questionType + ".";
            warnings.add(message);
            return new RuntimeRubricQa(
                    agentStep("RUN_TEST_GRADE", "SKIPPED", message),
                    findings,
                    warnings);
        }

        try {
            String rubricJson = objectMapper.writeValueAsString(rubric);
            RubricTestGradeResponse result = switch (questionType) {
                case "CREATE_TABLE" -> testGradeCreateTable(new TestGradeCreateTableRequest(
                        correctQuery,
                        correctQuery,
                        rubricJson,
                        request.totalPoints()));
                case "INSERT_DATA" -> testGradeInsert(new TestGradeInsertRequest(
                        request.examId(),
                        correctQuery,
                        correctQuery,
                        rubricJson,
                        request.totalPoints()));
                case "SELECT_QUERY" -> testGradeSelect(new TestGradeSelectRequest(
                        request.examId(),
                        correctQuery,
                        correctQuery,
                        rubricJson,
                        request.totalPoints()));
                case "FUNCTION", "STORED_PROCEDURE", "PROCEDURE", "ROUTINE" ->
                        testGradeRoutine(new TestGradeRoutineRequest(
                                request.examId(),
                                correctQuery,
                                correctQuery,
                                rubricJson,
                                request.totalPoints()));
                case "TRIGGER" -> testGradeTrigger(new TestGradeTriggerRequest(
                        request.examId(),
                        correctQuery,
                        correctQuery,
                        rubricJson,
                        request.totalPoints()));
                default -> null;
            };

            if (result == null) {
                String message = "Không có runner chấm thử cho loại câu hỏi " + questionType + ".";
                warnings.add(message);
                return new RuntimeRubricQa(agentStep("RUN_TEST_GRADE", "SKIPPED", message), findings, warnings);
            }

            double expected = request.totalPoints();
            boolean fullScore = result.allPassed() && Math.abs(result.earnedPoints() - expected) <= 0.05;
            if (fullScore) {
                return new RuntimeRubricQa(
                        agentStep("RUN_TEST_GRADE", "PASS", "SQL đáp án chuẩn đạt điểm tối đa khi chấm thử."),
                        findings,
                        warnings);
            }

            String detail = summarizeGradeDetails(result.details());
            String message = "SQL đáp án chuẩn chưa đạt điểm tối đa khi chấm thử: "
                    + roundForMessage(result.earnedPoints()) + "/" + roundForMessage(expected)
                    + (detail.isBlank() ? "" : ". " + detail);
            findings.add(agentFinding("ERROR", "REFERENCE_GRADE_FAILED", message));
            return new RuntimeRubricQa(agentStep("RUN_TEST_GRADE", "FAIL", message), findings, warnings);
        } catch (Exception e) {
            String message = "Chấm thử runtime thất bại: " + e.getMessage();
            findings.add(agentFinding("WARNING", "REFERENCE_GRADE_ERROR", message));
            warnings.add(message);
            return new RuntimeRubricQa(agentStep("RUN_TEST_GRADE", "WARN", message), findings, warnings);
        }
    }

    private String buildRubricAgentRepairInstruction(
            RubricAgentRunRequest request,
            List<RubricAgentRunResponse.RubricAgentFinding> findings,
            RuntimeRubricQa runtimeQa) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hãy đóng vai Rubric QA & Auto-Repair Agent. ")
                .append("Sửa rubric hiện tại để rubric hợp lệ, bao phủ tốt hơn và SQL đáp án chuẩn đạt điểm tối đa khi chấm thử. ")
                .append("Không tạo rubric mới từ đầu nếu không cần; giữ các field không liên quan.\n\n");

        if (request.teacherInstruction() != null && !request.teacherInstruction().isBlank()) {
            sb.append("Ghi chú thêm của giáo viên:\n")
                    .append(request.teacherInstruction().trim())
                    .append("\n\n");
        }

        sb.append("Các vấn đề QA phát hiện:\n");
        for (RubricAgentRunResponse.RubricAgentFinding finding : findings) {
            if ("INFO".equalsIgnoreCase(finding.severity())) {
                continue;
            }
            sb.append("- [")
                    .append(finding.severity())
                    .append("] ")
                    .append(finding.code())
                    .append(": ")
                    .append(finding.message())
                    .append("\n");
        }

        if (runtimeQa != null && runtimeQa.step() != null) {
            sb.append("\nKết quả RUN_TEST_GRADE:\n- ")
                    .append(runtimeQa.step().status())
                    .append(": ")
                    .append(runtimeQa.step().message())
                    .append("\n");
        }

        sb.append("\nYêu cầu trả về full rubric JSON trong field rubric, kèm changeSummary và warnings.");
        return sb.toString();
    }

    private RubricAgentRunResponse.RubricAgentStep summarizeFindingsStep(
            String tool,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        long errors = findings.stream().filter(f -> "ERROR".equalsIgnoreCase(f.severity())).count();
        long warnings = findings.stream().filter(f -> "WARNING".equalsIgnoreCase(f.severity())).count();
        if (errors > 0) {
            return agentStep(tool, "FAIL", "Phát hiện " + errors + " lỗi và " + warnings + " cảnh báo.");
        }
        if (warnings > 0) {
            return agentStep(tool, "WARN", "Phát hiện " + warnings + " cảnh báo cần giáo viên rà soát.");
        }
        return agentStep(tool, "PASS", "Không phát hiện vấn đề cấu trúc đáng kể.");
    }

    private RubricAgentRunResponse.RubricAgentStep agentStep(String tool, String status, String message) {
        return new RubricAgentRunResponse.RubricAgentStep(tool, status, message);
    }

    private RubricAgentRunResponse.RubricAgentFinding agentFinding(
            String severity,
            String code,
            String message) {
        return new RubricAgentRunResponse.RubricAgentFinding(severity, code, message);
    }

    private JsonNode resolveAgentPayload(JsonNode rubric) {
        JsonNode payload = rubric.path("grading_payload");
        if (payload != null && payload.isObject()) {
            return payload;
        }
        return rubric;
    }

    private JsonNode firstArray(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String fieldName : fieldNames) {
            String text = node.path(fieldName).asText("");
            if (!text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    private String normalizeAgentQuestionType(String questionType) {
        if (questionType == null || questionType.isBlank()) {
            return "CREATE_TABLE";
        }
        String normalized = questionType.trim().toUpperCase(Locale.ROOT);
        if ("PROCEDURE".equals(normalized)) {
            return "STORED_PROCEDURE";
        }
        return normalized;
    }

    private String displayName(String value) {
        return value == null || value.isBlank() ? "(chưa đặt tên)" : value;
    }

    private String roundForMessage(double value) {
        if (!Double.isFinite(value)) {
            return "NaN";
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String summarizeGradeDetails(List<Map<String, Object>> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        return details.stream()
                .map(detail -> detail == null ? "" : Objects.toString(detail.get("message"), ""))
                .filter(message -> !message.isBlank())
                .limit(3)
                .reduce((left, right) -> left + " | " + right)
                .orElse("");
    }

    private List<String> distinctStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private double calculateRubricAgentConfidence(
            List<RubricAgentRunResponse.RubricAgentStep> steps,
            List<RubricAgentRunResponse.RubricAgentFinding> findings) {
        double confidence = 0.95;
        for (RubricAgentRunResponse.RubricAgentFinding finding : findings) {
            if ("ERROR".equalsIgnoreCase(finding.severity())) {
                confidence -= 0.18;
            } else if ("WARNING".equalsIgnoreCase(finding.severity())) {
                confidence -= 0.07;
            }
        }
        for (RubricAgentRunResponse.RubricAgentStep step : steps) {
            if ("FAIL".equalsIgnoreCase(step.status())) {
                confidence -= 0.08;
            } else if ("SKIPPED".equalsIgnoreCase(step.status())) {
                confidence -= 0.04;
            }
        }
        return Math.max(0.20, Math.min(0.98, BigDecimal.valueOf(confidence)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue()));
    }

    private record RubricAgentObservation(
            JsonNode rubric,
            List<RubricAgentRunResponse.RubricAgentFinding> findings,
            RuntimeRubricQa runtimeQa,
            boolean normalizedChanged) {
    }

    private record DerivedExpectedValuesQa(
            RubricAgentRunResponse.RubricAgentStep step,
            List<RubricAgentRunResponse.RubricAgentFinding> findings,
            List<String> warnings) {
    }

    private record RuntimeRubricQa(
            RubricAgentRunResponse.RubricAgentStep step,
            List<RubricAgentRunResponse.RubricAgentFinding> findings,
            List<String> warnings) {
    }

    private String buildPriorQuestionContext(List<GenerateGradingRubricRequest.ContextQuery> contextQueries) {
        try {
            if (contextQueries == null || contextQueries.isEmpty()) {
                return "";
            }

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
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<String> readStringArray(JsonNode root, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode values = root.get(fieldName);
            if (values == null || !values.isArray()) {
                continue;
            }

            List<String> result = new ArrayList<>();
            values.forEach(value -> {
                if (value != null && !value.isNull()) {
                    String text = value.asText("");
                    if (!text.isBlank()) {
                        result.add(text);
                    }
                }
            });
            return result;
        }

        return List.of();
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

                BigDecimal blackboxScore = fakeSubmission.getScoreEarned() != null
                        ? fakeSubmission.getScoreEarned()
                        : BigDecimal.ZERO;
                WhiteboxResult whitebox = whiteboxEngine.evaluateFromPayload(
                        QuestionType.INSERT_DATA.name(), studentQuery, payload,
                        BigDecimal.valueOf(totalPoints), false);
                BigDecimal whiteboxDeduction = whitebox.cappedDeduction() == null
                        ? BigDecimal.ZERO : whitebox.cappedDeduction();
                BigDecimal finalScore = blackboxScore.subtract(whiteboxDeduction)
                        .setScale(2, RoundingMode.HALF_UP);
                if (finalScore.signum() < 0) {
                    finalScore = BigDecimal.ZERO;
                }
                if (!whitebox.isEmpty()) {
                    appendWhiteboxDetails(details, whitebox, whiteboxDeduction);
                }
                fakeSubmission.setScoreEarned(finalScore);

                double earnedPoints = finalScore.doubleValue();
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
                        details,
                        totalDeduction,
                        blackboxScore.setScale(2, RoundingMode.HALF_UP).doubleValue(),
                        whiteboxDeduction.setScale(2, RoundingMode.HALF_UP).doubleValue());
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

                List<Map<String, Object>> rawExpectedData = examSchemaService.executeAdminSql(
                        "SELECT * FROM [" + safeSchema + "].[" + safeTableName + "]")
                        .getResultSet();
                List<Map<String, Object>> expectedData = normalizeInsertExpectedDataForRubric(rawExpectedData);

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
                        expectedData));
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

    private List<Map<String, Object>> normalizeInsertExpectedDataForRubric(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> normalizedRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalizedRow = new LinkedHashMap<>();
            if (row != null) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    normalizedRow.put(entry.getKey(), normalizeInsertExpectedValueForRubric(entry.getValue()));
                }
            }
            normalizedRows.add(normalizedRow);
        }
        return normalizedRows;
    }

    private Object normalizeInsertExpectedValueForRubric(Object value) {
        if (value instanceof java.sql.Date
                || value instanceof java.sql.Timestamp
                || value instanceof java.sql.Time
                || value instanceof java.time.LocalDate
                || value instanceof java.time.LocalDateTime
                || value instanceof java.time.LocalTime
                || value instanceof java.time.OffsetDateTime) {
            return gradingSupport.normalizeValueStr(value, false, false);
        }
        return value;
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
                boolean hasStructuredForeignKeys = tableMetadata.getForeignKeys() != null
                        && !tableMetadata.getForeignKeys().isEmpty();
                boolean hasStructuredConstraints = tableMetadata.getConstraints() != null
                        && !tableMetadata.getConstraints().isEmpty();

                for (TableMetadata.ColumnMetadata column : tableMetadata.getColumns()) {
                    columns.add(new BuildCreateTablesResponse.CreateColumnConfig(
                            column.getColumnName(),
                            column.getRawDataType(),
                            column.isNullable(),
                            column.isAutoIncrement()));

                    if (!hasStructuredConstraints && column.isPrimaryKey()) {
                        primaryKeyColumns.add(column.getColumnName());
                    }

                    if (hasStructuredConstraints || hasStructuredForeignKeys) {
                        continue;
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
                if (hasStructuredConstraints) {
                    for (TableMetadata.ConstraintMetadata constraint : tableMetadata.getConstraints()) {
                        constraints.add(new BuildCreateTablesResponse.CreateConstraintConfig(
                                constraint.getConstraintName(),
                                constraint.getType(),
                                List.copyOf(constraint.getColumns()),
                                constraint.getReferencesTable(),
                                constraint.getReferencesColumns().isEmpty()
                                        ? null
                                        : List.copyOf(constraint.getReferencesColumns()),
                                constraint.getExpression(),
                                constraint.getDefaultValue()));
                    }
                } else if (hasStructuredForeignKeys) {
                    if (!primaryKeyColumns.isEmpty()) {
                        constraints.add(new BuildCreateTablesResponse.CreateConstraintConfig(
                                null,
                                "PRIMARY_KEY",
                                List.copyOf(primaryKeyColumns),
                                null,
                                null,
                                null,
                                null));
                    }
                    for (TableMetadata.ForeignKeyMetadata foreignKey : tableMetadata.getForeignKeys()) {
                        constraints.add(new BuildCreateTablesResponse.CreateConstraintConfig(
                                foreignKey.getConstraintName(),
                                "FOREIGN_KEY",
                                List.copyOf(foreignKey.getColumns()),
                                foreignKey.getReferencesTable(),
                                foreignKey.getReferencesColumns().isEmpty()
                                        ? null
                                        : List.copyOf(foreignKey.getReferencesColumns()),
                                null,
                                null));
                    }
                } else {
                    if (!primaryKeyColumns.isEmpty()) {
                        constraints.add(new BuildCreateTablesResponse.CreateConstraintConfig(
                                null,
                                "PRIMARY_KEY",
                                List.copyOf(primaryKeyColumns),
                                null,
                                null,
                                null,
                                null));
                    }
                    for (CreateForeignKeyGroup group : foreignKeyGroups.values()) {
                        constraints.add(new BuildCreateTablesResponse.CreateConstraintConfig(
                                null,
                                "FOREIGN_KEY",
                                List.copyOf(group.columns()),
                                group.referencesTable(),
                                group.referencesColumns().isEmpty() ? null : List.copyOf(group.referencesColumns()),
                                null,
                                null));
                    }
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
            SelectRubricPenaltyNormalizer.normalize(rubric, totalPoints);
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

                        checkDatasetAdequacy(caseId, correctQuery, teacherRows, details);
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

            RubricTestGradeResponse blackbox = RubricTestGradeResponse.of(
                    finalEarned.doubleValue(),
                    totalPoints,
                    allPassed && totalDeduction.compareTo(BigDecimal.ZERO) <= 0,
                    details,
                    totalDeduction.setScale(2, RoundingMode.HALF_UP).doubleValue());
            return applyWhiteboxPreviewResponse(
                    QuestionType.SELECT_QUERY.name(),
                    studentQuery,
                    payload,
                    totalPoints,
                    blackbox);

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
            RubricTestGradeResponse blackbox = RubricTestGradeResponse.of(
                    earned.doubleValue(),
                    totalPoints,
                    decision.isCorrect(),
                    List.of(Map.of(
                            "type", decision.isCorrect() ? "success" : "error",
                            "message", message,
                            "points", earned.doubleValue())),
                    Math.max(0d, totalPoints - earned.doubleValue()));
            return applyWhiteboxPreviewResponse(
                    QuestionType.SELECT_QUERY.name(),
                    request.studentQuery(),
                    gradingPayloadNode(question),
                    totalPoints,
                    blackbox);
        } finally {
            try {
                examSchemaService.dropSchema(previewSchema);
            } catch (Exception ignore) {
            }
        }
    }

    private com.fasterxml.jackson.databind.JsonNode gradingPayloadNode(ExamQuestion question) {
        if (question == null || question.getGradingRubric() == null || question.getGradingRubric().isBlank()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(question.getGradingRubric()).path("grading_payload");
        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
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
                RubricTestGradeResponse blackbox = RubricTestGradeResponse.of(
                        0,
                        totalPoints,
                        false,
                        List.of(
                                Map.of("type", "error", "message",
                                        "Lỗi cú pháp SQL: " + e.getMessage(), "points", 0)));
                return applyCreateTableWhiteboxPreview(
                        studentQuery,
                        gradingPayloadNode(gradingRubric),
                        totalPoints,
                        blackbox);
            }

            return executeRubricGradingV2(
                    studentSchema, teacherSchema, gradingRubric, totalPoints, studentQuery);

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
                    studentSchema, teacherSchema, gradingRubric, totalPoints, studentQuery);

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
     * Runs the cheap authoring data-adequacy checks on the teacher reference rows already produced
     * for this test case and surfaces any degenerate-dataset warnings to the teacher. Advisory only
     * — it appends response details and never blocks. The query's aggregate/GROUP BY shape is passed
     * to the linter so the output-shape checks are skipped for scalar-aggregate queries (whose single
     * row is correct) and the column-NULL check is skipped when an aggregate may be NULL by design.
     */
    private void checkDatasetAdequacy(
            String caseId,
            String correctQuery,
            List<Map<String, Object>> teacherRows,
            List<Map<String, Object>> details) {
        QueryStructureFacts facts = queryStructureAnalyzer.analyze(correctQuery);
        boolean aggregatePresent = facts.parseOk() && !facts.aggregateFns().isEmpty();
        boolean groupByPresent = facts.parseOk() && facts.hasGroupBy();
        for (SelectDatasetAdequacyLinter.Finding finding
                : adequacyLinter.lint(teacherRows, aggregatePresent, groupByPresent)) {
            String prefix = finding.severity() == SelectDatasetAdequacyLinter.Severity.HARD_WARN
                    ? "[" + caseId + "] ⚠ Dữ liệu test case yếu: "
                    : "[" + caseId + "] Dữ liệu test case: ";
            details.add(Map.of(
                    "type", "warning",
                    "message", prefix + finding.message(),
                    "points", 0));
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
            try {
                executeSetupThenRoutine(examSchemaService, teacherSchema, setupScript, correctQuery);
            } catch (Exception e) {
                return RubricTestGradeResponse.of(
                        0,
                        totalPoints,
                        false,
                        List.of(
                                Map.of("type", "error", "message",
                                        "Lỗi cú pháp SQL trong đáp án mẫu: " + e.getMessage(), "points", 0)));
            }

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

        List<SelectResultDiff.SelectResultEdit> edits =
                SelectResultDiff.collectColumnEdits(expectedColumns, actualColumns);
        if (edits.isEmpty()) {
            return BigDecimal.ZERO;
        }

        SelectResultScorer.ScoringResult result =
                SelectResultScorer.score(edits, selectRules, maxTotalPoints, gradingSupport);

        StringBuilder issueBuilder = new StringBuilder();
        for (SelectResultScorer.AppliedEdit applied : result.applied()) {
            appendSelectIssue(issueBuilder, SelectResultScorer.describe(applied));
        }

        BigDecimal rounded = result.totalDeduction().setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.ZERO) <= 0) {
            details.add(Map.of(
                    "type", "info",
                    "message", "[\u0110i\u1ec3m c\u1ea5u tr\u00fac c\u1ed9t] " + issueBuilder.toString().trim(),
                    "points", 0));
            return BigDecimal.ZERO;
        }

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

        JsonNode cellNotEqualRule = gradingSupport.findInsertRule(selectRules, "CELL_VALUE", "NOT_EQUAL");
        JsonNode cellNullRule = gradingSupport.findInsertRule(selectRules, "CELL_VALUE", "IS_NULL");
        JsonNode cellCompareModifiers = gradingSupport.firstNonEmptyModifiers(
                gradingSupport.extractInsertRuleModifiers(cellNotEqualRule),
                gradingSupport.extractInsertRuleModifiers(cellNullRule));

        // SORT_ASC on the ROW_ORDER rule means "accept any row order" -> grade order-insensitive.
        JsonNode rowOrderRule = gradingSupport.findInsertRule(selectRules, "ROW_ORDER", "OUT_OF_ORDER");
        boolean orderSensitive = strictOrdering
                && !(rowOrderRule != null && gradingSupport.hasInsertModifier(rowOrderRule, "SORT_ASC"));

        List<SelectResultDiff.SelectResultEdit> edits = SelectResultDiff.collectRowAndCellEdits(
                effectiveExpectedColumns,
                actualColumns,
                expectedRowMaps,
                safeActualRows,
                orderSensitive,
                cellCompareModifiers,
                gradingSupport);
        if (edits.isEmpty()) {
            details.add(Map.of(
                    "type", "success",
                    "message", "[" + caseId + "] " + caseName + ": Khớp hoàn toàn kết quả, không bị trừ điểm",
                    "points", 0));
            return BigDecimal.ZERO;
        }

        SelectResultScorer.ScoringResult result =
                SelectResultScorer.score(edits, selectRules, caseMaxPenalty, gradingSupport);
        StringBuilder issueBuilder = new StringBuilder();
        for (SelectResultScorer.AppliedEdit applied : result.applied()) {
            appendSelectIssue(issueBuilder, SelectResultScorer.describe(applied));
        }

        BigDecimal roundedDeduction = result.totalDeduction().setScale(2, RoundingMode.HALF_UP);
        boolean allChecksPassed = roundedDeduction.compareTo(BigDecimal.ZERO) <= 0;
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

    private void appendSelectIssue(StringBuilder builder, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(message.trim());
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

    private RubricTestGradeResponse executeRubricGradingV2(
            String studentSchema,
            String teacherSchema,
            String gradingRubricJson,
            double totalPoints,
            String studentQuery) {
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

        RubricTestGradeResponse blackbox = RubricTestGradeResponse.of(
                result.earnedPoints().doubleValue(),
                totalPoints,
                result.allPassed(),
                result.details(),
                result.totalDeductions().doubleValue());
        return applyCreateTableWhiteboxPreview(
                studentQuery, rubric.path("grading_payload"), totalPoints, blackbox);
    }

    private RubricTestGradeResponse applyCreateTableWhiteboxPreview(
            String studentQuery,
            JsonNode gradingPayload,
            double totalPoints,
            RubricTestGradeResponse blackbox) {
        WhiteboxResult whitebox = whiteboxEngine.evaluateFromPayload(
                QuestionType.CREATE_TABLE.name(),
                studentQuery,
                gradingPayload,
                BigDecimal.valueOf(totalPoints),
                false);
        if (whitebox.isEmpty()) {
            return blackbox;
        }

        List<Map<String, Object>> details = new ArrayList<>(blackbox.details());
        for (WhiteboxViolation violation : whitebox.violations()) {
            String type = switch (violation.status()) {
                case FAIL -> "error";
                case WARN, UNVERIFIED -> "warning";
                case PASS -> "success";
            };
            String evidence = violation.actual() == null || violation.actual().isBlank()
                    ? ""
                    : ": " + violation.actual();
            details.add(Map.of(
                    "type", type,
                    "message", "[Whitebox] " + violation.label() + evidence,
                    "points", violation.deductedPoints().negate().doubleValue()));
        }

        double deduction = whitebox.cappedDeduction().doubleValue();
        double finalScore = Math.max(0, blackbox.earnedPoints() - deduction);
        double blackboxDeductions = blackbox.totalDeductions() == null
                ? 0
                : blackbox.totalDeductions();
        return RubricTestGradeResponse.withWhitebox(
                blackbox.earnedPoints(),
                deduction,
                finalScore,
                totalPoints,
                blackbox.allPassed() && deduction == 0,
                details,
                blackboxDeductions + deduction);
    }

    private JsonNode gradingPayloadNode(String gradingRubric) {
        if (gradingRubric == null || gradingRubric.isBlank()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(gradingRubric).path("grading_payload");
        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
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

    private void appendWhiteboxDetails(
            List<Map<String, Object>> details,
            WhiteboxResult whitebox,
            BigDecimal whiteboxDeduction) {
        if (whitebox == null || whitebox.isEmpty()) {
            return;
        }

        if (whiteboxDeduction != null && whiteboxDeduction.signum() > 0) {
            details.add(Map.of(
                    "type", "warning",
                    "message", "[Whitebox] Tổng trừ "
                            + whiteboxDeduction.setScale(2, RoundingMode.HALF_UP).toPlainString()
                            + " điểm do vi phạm quy tắc phương pháp.",
                    "points", -whiteboxDeduction.setScale(2, RoundingMode.HALF_UP).doubleValue()));
        }

        for (WhiteboxViolation violation : whitebox.violations()) {
            String status = violation.status() == null ? "" : violation.status().name();
            if ("PASS".equals(status)) {
                continue;
            }
            String type = "FAIL".equals(status) ? "error"
                    : ("WARN".equals(status) ? "warning" : "info");
            BigDecimal deducted = violation.deductedPoints() == null
                    ? BigDecimal.ZERO : violation.deductedPoints();
            String evidence = violation.actual() == null || violation.actual().isBlank()
                    ? ""
                    : " | SQL: " + violation.actual();
            String label = violation.label() == null ? violation.ruleId() : violation.label();
            String reason = violation.reason() == null ? status : violation.reason();
            details.add(Map.of(
                    "type", type,
                    "message", "[Whitebox] " + label + " - " + reason + evidence,
                    "points", -deducted.setScale(2, RoundingMode.HALF_UP).doubleValue()));
        }
    }

    private RubricTestGradeResponse applyWhiteboxPreviewResponse(
            String questionType,
            String studentQuery,
            JsonNode gradingPayload,
            double totalPoints,
            RubricTestGradeResponse blackbox) {
        BigDecimal questionPoints = BigDecimal.valueOf(totalPoints);
        WhiteboxResult whitebox = whiteboxEngine.evaluateFromPayload(
                questionType,
                studentQuery,
                gradingPayload,
                questionPoints,
                false);

        BigDecimal whiteboxDeduction = whitebox.cappedDeduction() == null
                ? BigDecimal.ZERO
                : whitebox.cappedDeduction();
        BigDecimal blackboxScore = BigDecimal.valueOf(blackbox.earnedPoints())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalScore = blackboxScore.subtract(whiteboxDeduction)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        List<Map<String, Object>> details = new ArrayList<>(blackbox.details());
        if (!whitebox.isEmpty()) {
            appendWhiteboxDetails(details, whitebox, whiteboxDeduction);
        }

        double blackboxDeductions = blackbox.totalDeductions() == null
                ? Math.max(0d, totalPoints - blackbox.earnedPoints())
                : blackbox.totalDeductions();
        return RubricTestGradeResponse.withWhitebox(
                blackboxScore.doubleValue(),
                whiteboxDeduction.setScale(2, RoundingMode.HALF_UP).doubleValue(),
                finalScore.doubleValue(),
                totalPoints,
                blackbox.allPassed() && whiteboxDeduction.signum() <= 0,
                details,
                blackboxDeductions + whiteboxDeduction.doubleValue());
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

    private List<Double> normalizedTriggerWeights(JsonNode testCases) {
        if (testCases == null || !testCases.isArray() || testCases.isEmpty()) {
            return List.of();
        }

        List<Double> rawWeights = new ArrayList<>();
        double sum = 0d;
        for (JsonNode tc : testCases) {
            double weight = Math.abs(rawTriggerWeight(tc));
            rawWeights.add(weight);
            sum += weight;
        }

        if (sum <= 0d) {
            double equal = 1d / rawWeights.size();
            return rawWeights.stream().map(ignored -> equal).toList();
        }

        double divisor = sum;
        return rawWeights.stream().map(weight -> weight / divisor).toList();
    }

    private double rawTriggerWeight(JsonNode testCase) {
        if (testCase == null || !testCase.isObject()) {
            return 1d;
        }
        JsonNode scoreWeight = testCase.get("score_weight");
        if (scoreWeight != null && scoreWeight.isNumber()) {
            return scoreWeight.asDouble();
        }
        JsonNode penaltyValue = testCase.get("penalty_value");
        if (penaltyValue != null && penaltyValue.isNumber()) {
            return penaltyValue.asDouble();
        }
        return 1d;
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
                            .parameterMode(p.path("expected_mode").asText("").trim())
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
            double totalPoints,
            String studentQuery) {

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

            if (expectedRoutines.isEmpty()) {
                details.add(Map.of(
                        "type", "error",
                        "message", "Không tìm thấy routine trong đáp án chuẩn",
                        "points", 0));
                return RubricTestGradeResponse.of(0, totalPoints, false, details);
            }

            boolean storedProcedureRubric = isStoredProcedureRubric(rubric, expectedRoutines);
            if (!storedProcedureRubric) {
                return executeFunctionRubricGrading(
                        studentSchema,
                        teacherSchema,
                        gradingPayload,
                        testCases,
                        expectedRoutines,
                        actualRoutines,
                        caseSensitiveNames,
                        positiveOnlyScoring,
                        printOutputCompareMode,
                        totalPoints,
                        studentQuery,
                        details);
            }
            return executeStoredProcedureRubricGrading(
                    studentSchema,
                    teacherSchema,
                    gradingPayload,
                    testCases,
                    expectedRoutines,
                    actualRoutines,
                    caseSensitiveNames,
                    positiveOnlyScoring,
                    printOutputCompareMode,
                    totalPoints,
                    studentQuery,
                    details);

        } catch (Exception e) {
            details.add(Map.of(
                    "type", "error",
                    "message", "Lỗi phân tích rubric: " + e.getMessage(),
                    "points", 0));
            return RubricTestGradeResponse.of(0, totalPoints, false, details);
        }
    }

    private RubricTestGradeResponse executeStoredProcedureRubricGrading(
            String studentSchema,
            String teacherSchema,
            JsonNode gradingPayload,
            JsonNode testCases,
            List<RoutineMetadata> expectedRoutines,
            List<RoutineMetadata> actualRoutines,
            boolean caseSensitiveNames,
            boolean positiveOnlyScoring,
            String printOutputCompareMode,
            double totalPoints,
            String studentQuery,
            List<Map<String, Object>> details) {
        if (!testCases.isArray() || testCases.isEmpty()) {
            details.add(Map.of(
                    "type", "error",
                    "message", "[Metadata] Thiếu test case: Stored Procedure không được fallback sang chấm điểm metadata.",
                    "points", 0));
            return applyStoredProcedureWhiteboxPreview(
                    gradingPayload, totalPoints, studentQuery, details, 0, false);
        }

        StoredProcedureMetadataGateValidator.ValidationResult metadataResult =
                StoredProcedureMetadataGateValidator.validate(
                        expectedRoutines, actualRoutines, caseSensitiveNames);
        if (!metadataResult.passed()) {
            for (StoredProcedureMetadataGateValidator.Violation violation : metadataResult.violations()) {
                details.add(Map.of(
                        "type", "error",
                        "message", "[Metadata] " + violation.message(),
                        "points", 0));
            }
            return applyStoredProcedureWhiteboxPreview(
                    gradingPayload, totalPoints, studentQuery, details, 0, false);
        }

        details.add(Map.of(
                "type", "success",
                "message", "[Metadata] Stored Procedure hợp lệ; test case là nguồn điểm duy nhất.",
                "points", 0));

        RoutineTestCaseGrade testCaseGrade = executeRoutineTestCases(
                studentSchema,
                teacherSchema,
                testCases,
                totalPoints,
                printOutputCompareMode,
                details);
        double finalScore = testCaseGrade.earnedPoints()
                .min(BigDecimal.valueOf(totalPoints))
                .max(BigDecimal.ZERO)
                .doubleValue();
        if (positiveOnlyScoring && finalScore < 0) {
            finalScore = 0;
        }
        return applyStoredProcedureWhiteboxPreview(
                gradingPayload,
                totalPoints,
                studentQuery,
                details,
                finalScore,
                testCaseGrade.allPassed());
    }

    private RubricTestGradeResponse applyStoredProcedureWhiteboxPreview(
            JsonNode gradingPayload,
            double totalPoints,
            String studentQuery,
            List<Map<String, Object>> details,
            double blackboxScore,
            boolean allPassed) {
        RubricTestGradeResponse blackbox = RubricTestGradeResponse.of(
                blackboxScore,
                totalPoints,
                allPassed,
                details,
                Math.max(0d, totalPoints - blackboxScore));
        return applyWhiteboxPreviewResponse(
                "STORED_PROCEDURE",
                studentQuery,
                gradingPayload,
                totalPoints,
                blackbox);
    }

    private RubricTestGradeResponse executeFunctionRubricGrading(
            String studentSchema,
            String teacherSchema,
            JsonNode gradingPayload,
            JsonNode testCases,
            List<RoutineMetadata> expectedRoutines,
            List<RoutineMetadata> actualRoutines,
            boolean caseSensitiveNames,
            boolean positiveOnlyScoring,
            String printOutputCompareMode,
            double totalPoints,
            String studentQuery,
            List<Map<String, Object>> details) {
        if (!testCases.isArray() || testCases.isEmpty()) {
            details.add(Map.of(
                    "type", "error",
                    "message", "[Metadata] Thiếu test case: Function không được fallback sang chấm điểm metadata.",
                    "points", 0));
            return applyFunctionWhiteboxPreview(
                    gradingPayload, totalPoints, studentQuery, details, 0, false);
        }

        FunctionMetadataContractValidator.ValidationResult metadataResult =
                FunctionMetadataContractValidator.validate(
                        expectedRoutines, actualRoutines, caseSensitiveNames);
        if (!metadataResult.passed()) {
            for (FunctionMetadataContractValidator.Violation violation : metadataResult.violations()) {
                details.add(Map.of(
                        "type", "error",
                        "message", "[Metadata] " + violation.message(),
                        "points", 0));
            }
            return applyFunctionWhiteboxPreview(
                    gradingPayload, totalPoints, studentQuery, details, 0, false);
        }

        details.add(Map.of(
                "type", "success",
                "message", "[Metadata] Function hợp lệ; test case là nguồn điểm duy nhất.",
                "points", 0));

        RoutineTestCaseGrade testCaseGrade = executeRoutineTestCases(
                studentSchema,
                teacherSchema,
                testCases,
                totalPoints,
                printOutputCompareMode,
                details);
        double finalScore = testCaseGrade.earnedPoints()
                .min(BigDecimal.valueOf(totalPoints))
                .max(BigDecimal.ZERO)
                .doubleValue();
        if (positiveOnlyScoring && finalScore < 0) {
            finalScore = 0;
        }
        return applyFunctionWhiteboxPreview(
                gradingPayload,
                totalPoints,
                studentQuery,
                details,
                finalScore,
                testCaseGrade.allPassed());
    }

    private RubricTestGradeResponse applyFunctionWhiteboxPreview(
            JsonNode gradingPayload,
            double totalPoints,
            String studentQuery,
            List<Map<String, Object>> details,
            double blackboxScore,
            boolean allPassed) {
        RubricTestGradeResponse blackbox = RubricTestGradeResponse.of(
                blackboxScore,
                totalPoints,
                allPassed,
                details,
                Math.max(0d, totalPoints - blackboxScore));
        return applyWhiteboxPreviewResponse(
                "FUNCTION",
                studentQuery,
                gradingPayload,
                totalPoints,
                blackbox);
    }

    private static final String VALIDATION_MARKER_COLUMN = "__VALIDATION_MARKER__";

    private RoutineTestCaseGrade executeRoutineTestCases(
            String studentSchema,
            String teacherSchema,
            JsonNode testCases,
            double maxPoints,
            String printOutputCompareMode,
            List<Map<String, Object>> details) {
        List<BigDecimal> rawWeights = new ArrayList<>(testCases.size());
        for (JsonNode tc : testCases) {
            rawWeights.add(BigDecimal.valueOf(readTestCaseWeight(tc)));
        }
        List<BigDecimal> normalizedWeights = TestCaseWeightNormalizer.normalize(rawWeights);
        BigDecimal maxPointsValue = BigDecimal.valueOf(maxPoints);

        BigDecimal earned = BigDecimal.ZERO;
        boolean allPassed = true;

        for (int i = 0; i < testCases.size(); i++) {
            JsonNode tc = testCases.get(i);
            String caseName = textOrDefault(tc, "case_name", "TC" + (i + 1));
            BigDecimal casePoints = maxPointsValue.multiply(normalizedWeights.get(i));

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
                    earned = earned.add(casePoints);
                    details.add(Map.of(
                            "type", "success",
                            "message", String.format("Test case '%s': đúng", caseName),
                            "points", roundTo2(casePoints.doubleValue())));
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

    private record RoutineTestCaseGrade(BigDecimal earnedPoints, boolean allPassed) {
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
            JsonNode testCases = gradingPayload.path("test_cases");

            // Trigger metadata scoring (existence / table / event / timing) removed.
            // Grading is now driven purely by test cases and white-box rules.
            // Match runtime: score_weight is a normalized ratio, then multiplied by totalPoints.
            double earnedWeight = 0d;
            boolean allPassed = true;

            // Execute test cases if defined
            if (testCases.isArray() && testCases.size() > 0) {
                List<Double> normalizedWeights = normalizedTriggerWeights(testCases);
                int caseIndex = 0;
                for (JsonNode tc : testCases) {
                    String caseName = tc.path("case_name").asText("Unnamed");
                    String setupScript = tc.path("setup_script").asText("");

                    String invocationQuery = tc.path("invocation_query").asText("");
                    String validationQuery = tc.path("validation_query").asText("");
                    String verificationType = tc.path("verification_type").asText("SIDE_EFFECT");
                    double scoreWeight = caseIndex < normalizedWeights.size()
                            ? normalizedWeights.get(caseIndex)
                            : 0d;
                    double casePoints = roundTo2(totalPoints * scoreWeight);
                    caseIndex++;

                    if (invocationQuery.isBlank()) {
                        details.add(Map.of(
                                "type", "error",
                                "message",
                                String.format("Test case '%s': lỗi cấu hình (thiếu invocation_query)", caseName),
                                "points", -casePoints));
                        allPassed = false;
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

                        // EXECUTION_STATUS checks whether invocation succeeds/fails. For backward
                        // compatibility with older trigger rubrics, a blank validation_query also
                        // means status-only. If the reference trigger itself rejects the DML, fall
                        // back to status comparison instead of reporting "teacher answer failed".
                        boolean executionStatusOnly = "EXECUTION_STATUS".equalsIgnoreCase(verificationType)
                                || validationQuery.isBlank();
                        boolean checkSideEffect = !executionStatusOnly
                                && "SIDE_EFFECT".equalsIgnoreCase(verificationType);

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

                        // For status-only TC's, or SIDE_EFFECT TC's whose reference trigger rejects
                        // the DML, compare execution status (both reject or both accept).
                        if (executionStatusOnly || teacherInvocationFailed || studentInvocationFailed) {
                            if (teacherInvocationFailed == studentInvocationFailed) {
                                if (teacherInvocationFailed) {
                                    earnedWeight += scoreWeight;
                                    details.add(Map.of(
                                            "type", "success",
                                            "message",
                                            String.format(
                                                    "Test case '%s': Đạt (trigger đã từ chối giao dịch đúng như kỳ vọng)",
                                                    caseName),
                                            "points", 0));
                                } else {
                                    earnedWeight += scoreWeight;
                                    details.add(Map.of(
                                            "type", "success",
                                            "message",
                                            String.format(
                                                    "Test case '%s': Đạt (trigger đã chấp nhận giao dịch đúng như kỳ vọng)",
                                                    caseName),
                                            "points", 0));
                                }
                            } else {
                                details.add(Map.of(
                                        "type", "error",
                                        "message",
                                        String.format("Test case '%s': Không đạt (trạng thái thực thi không khớp)", caseName),
                                        "points", -casePoints));
                                allPassed = false;
                            }
                            continue;
                        }

                        // For SIDE_EFFECT verification, compare validation query results
                        // Results were already captured in the batch execution above
                        if (checkSideEffect) {
                            if (teacherInvocationFailed) {
                                details.add(Map.of(
                                        "type", "error",
                                        "message",
                                        String.format("Test case '%s': lỗi khi chạy đáp án chuẩn - %s",
                                                caseName, teacherInvocationError != null
                                                        ? teacherInvocationError : "không rõ lỗi"),
                                        "points", -casePoints));
                                allPassed = false;
                                continue;
                            }
                            if (studentInvocationFailed) {
                                details.add(Map.of(
                                        "type", "error",
                                        "message",
                                        String.format("Test case '%s': Không đạt (lỗi khi chạy bài làm - %s)",
                                                caseName, studentInvocationError != null
                                                        ? studentInvocationError : "không rõ lỗi"),
                                        "points", -casePoints));
                                allPassed = false;
                                continue;
                            }

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
                                earnedWeight += scoreWeight;
                                details.add(Map.of(
                                        "type", "success",
                                        "message", String.format("Test case '%s': Đạt", caseName),
                                        "points", 0));
                            } else {
                                details.add(Map.of(
                                        "type", "error",
                                        "message", String.format("Test case '%s': Không đạt (kết quả không khớp)", caseName),
                                        "points", -casePoints));
                                allPassed = false;
                            }
                        }
                    } catch (Exception e) {
                        details.add(Map.of(
                                "type", "error",
                                "message", String.format("Test case '%s': ERROR - %s", caseName, e.getMessage()),
                                "points", -casePoints));
                        allPassed = false;
                    }
                }
            } else {
                details.add(Map.of(
                        "type", "error",
                        "message", "Rubric TRIGGER không có test_cases để chấm thử.",
                        "points", 0));
                allPassed = false;
            }

            double finalScore = roundTo2(Math.max(0d, Math.min(earnedWeight, 1d)) * totalPoints);

            finalScore = Math.max(0, Math.min(finalScore, totalPoints));
            RubricTestGradeResponse blackbox = RubricTestGradeResponse.of(
                    finalScore,
                    totalPoints,
                    allPassed,
                    details,
                    Math.max(0d, totalPoints - finalScore));
            return applyWhiteboxPreviewResponse(
                    QuestionType.TRIGGER.name(),
                    studentQuery,
                    gradingPayload,
                    totalPoints,
                    blackbox);

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
