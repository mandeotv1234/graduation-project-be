package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.util.*;
import graduation_project_be.application.usecases.GradingTraceCollector;

/** Grades FUNCTION / STORED_PROCEDURE questions. */
@Slf4j
@RequiredArgsConstructor
public class RoutineQuestionGrader {

    private final ExamSchemaService examSchemaService;
    private final ObjectMapper objectMapper;
    private final TestCaseRepository testCaseRepository;
    private final GradingSupport support;

    public boolean gradeRoutineAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question,
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

        if (question.getQuestionType() == QuestionType.FUNCTION) {
            return gradeFunctionWithMetadataGate(
                    schemaName,
                    teacherSchemaName,
                    question,
                    submission,
                    expectedRoutines,
                    actualRoutines,
                    totalPoints,
                    hasTestCases);
        }
        return gradeStoredProcedureWithMetadataGate(
                schemaName,
                teacherSchemaName,
                question,
                submission,
                expectedRoutines,
                actualRoutines,
                totalPoints,
                hasTestCases);
    }

    private boolean gradeStoredProcedureWithMetadataGate(
            String schemaName,
            String teacherSchemaName,
            ExamQuestion question,
            ExamSubmission submission,
            List<RoutineMetadata> expectedRoutines,
            List<RoutineMetadata> actualRoutines,
            BigDecimal totalPoints,
            boolean hasTestCases) {
        if (!hasTestCases) {
            String message = "[THIẾU TEST CASE] Stored Procedure phải có test case; metadata chỉ là điều kiện bắt buộc và không được dùng để tính điểm.";
            support.addTeacherConfigTrace(
                    GradingTraceItem.STATUS_FAIL,
                    "Thiếu test case Stored Procedure",
                    message,
                    totalPoints,
                    "Không có test case đã persist cho câu Stored Procedure; hệ thống không fallback sang chấm metadata.");
            setFailedSubmission(submission, message);
            return false;
        }

        boolean caseSensitiveNames = extractCaseSensitiveNames(question.getGradingRubric());
        StoredProcedureMetadataGateValidator.ValidationResult metadataResult =
                StoredProcedureMetadataGateValidator.validate(
                        expectedRoutines, actualRoutines, caseSensitiveNames);
        if (!metadataResult.passed()) {
            String message = buildStoredProcedureMetadataGateError(metadataResult);
            addStoredProcedureMetadataFailureTraces(metadataResult, totalPoints);
            setFailedSubmission(submission, message);
            return false;
        }

        if (GradingTraceCollector.isActive()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_METADATA_CHECK,
                    GradingTraceItem.STATUS_PASS,
                    "Metadata Stored Procedure hợp lệ",
                    "Stored Procedure đã vượt qua metadata gate; điểm được tính 100% từ test case.",
                    null,
                    null,
                    "STORED_PROCEDURE_CONTRACT",
                    "MATCH",
                    null,
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "Tên, loại và số lượng tham số",
                    "Hợp lệ",
                    "Metadata là điều kiện bắt buộc, không cộng điểm."));
        }

        boolean testCasesPassed = support.gradeByTestCases(
                schemaName, teacherSchemaName, question, submission);
        if (submission != null) {
            BigDecimal ratio = submission.getScoreEarned() != null
                    ? submission.getScoreEarned()
                    : BigDecimal.ZERO;
            ratio = ratio.max(BigDecimal.ZERO).min(BigDecimal.ONE);
            submission.setScoreEarned(totalPoints.multiply(ratio));
        }
        return testCasesPassed;
    }

    private void addStoredProcedureMetadataFailureTraces(
            StoredProcedureMetadataGateValidator.ValidationResult result,
            BigDecimal totalPoints) {
        if (!GradingTraceCollector.isActive()) {
            return;
        }
        for (StoredProcedureMetadataGateValidator.Violation violation : result.violations()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    violation.configurationError()
                            ? GradingTraceItem.KIND_TEACHER_CONFIG
                            : GradingTraceItem.KIND_METADATA_CHECK,
                    GradingTraceItem.STATUS_FAIL,
                    violation.configurationError()
                            ? "Cấu hình metadata Stored Procedure không hợp lệ"
                            : "Metadata Stored Procedure không khớp",
                    violation.message(),
                    null,
                    null,
                    "STORED_PROCEDURE_CONTRACT",
                    violation.code(),
                    violation.configurationError() ? "REVIEW_CONFIG" : null,
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    violation.expected(),
                    violation.actual(),
                    "Metadata gate thất bại; bỏ qua toàn bộ test case và chấm 0 điểm."));
        }
        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_SUMMARY,
                GradingTraceItem.STATUS_FAIL,
                "Stored Procedure không vượt qua metadata gate",
                buildStoredProcedureMetadataGateError(result),
                null,
                null,
                "STORED_PROCEDURE_CONTRACT",
                "GATE_FAILED",
                null,
                null,
                BigDecimal.ZERO,
                totalPoints,
                totalPoints,
                "Metadata contract hợp lệ",
                "Không hợp lệ",
                "Test case không được thực thi."));
    }

    private String buildStoredProcedureMetadataGateError(
            StoredProcedureMetadataGateValidator.ValidationResult result) {
        boolean configurationError = result.violations().stream()
                .anyMatch(StoredProcedureMetadataGateValidator.Violation::configurationError);
        String prefix = configurationError
                ? "[LỖI CẤU HÌNH METADATA] "
                : "[METADATA STORED PROCEDURE KHÔNG HỢP LỆ] ";
        StringJoiner messages = new StringJoiner(" ");
        result.violations().forEach(violation -> messages.add(violation.message()));
        return prefix + messages;
    }

    private boolean gradeFunctionWithMetadataGate(
            String schemaName,
            String teacherSchemaName,
            ExamQuestion question,
            ExamSubmission submission,
            List<RoutineMetadata> expectedRoutines,
            List<RoutineMetadata> actualRoutines,
            BigDecimal totalPoints,
            boolean hasTestCases) {
        if (!hasTestCases) {
            String message = "[THIẾU TEST CASE] Function phải có test case; metadata chỉ là điều kiện bắt buộc và không được dùng để tính điểm.";
            support.addTeacherConfigTrace(
                    GradingTraceItem.STATUS_FAIL,
                    "Thiếu test case Function",
                    message,
                    totalPoints,
                    "Không có test case đã persist cho câu Function; hệ thống không fallback sang chấm metadata.");
            setFailedSubmission(submission, message);
            return false;
        }

        boolean caseSensitiveNames = extractCaseSensitiveNames(question.getGradingRubric());
        FunctionMetadataContractValidator.ValidationResult metadataResult =
                FunctionMetadataContractValidator.validate(
                        expectedRoutines, actualRoutines, caseSensitiveNames);
        if (!metadataResult.passed()) {
            String message = buildMetadataGateError(metadataResult);
            addFunctionMetadataFailureTraces(metadataResult, totalPoints);
            setFailedSubmission(submission, message);
            return false;
        }

        if (GradingTraceCollector.isActive()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    GradingTraceItem.KIND_METADATA_CHECK,
                    GradingTraceItem.STATUS_PASS,
                    "Metadata Function hợp lệ",
                    "Function đã vượt qua metadata gate; điểm được tính 100% từ test case.",
                    null,
                    null,
                    "FUNCTION_CONTRACT",
                    "MATCH",
                    null,
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "Tên, loại, return type, số lượng/type/mode tham số",
                    "Hợp lệ",
                    "Metadata là điều kiện bắt buộc, không cộng điểm."));
        }

        boolean testCasesPassed = support.gradeByTestCases(
                schemaName, teacherSchemaName, question, submission);
        if (submission != null) {
            BigDecimal ratio = submission.getScoreEarned() != null
                    ? submission.getScoreEarned()
                    : BigDecimal.ZERO;
            ratio = ratio.max(BigDecimal.ZERO).min(BigDecimal.ONE);
            submission.setScoreEarned(totalPoints.multiply(ratio));
        }
        return testCasesPassed;
    }

    private void addFunctionMetadataFailureTraces(
            FunctionMetadataContractValidator.ValidationResult result,
            BigDecimal totalPoints) {
        if (!GradingTraceCollector.isActive()) {
            return;
        }
        for (FunctionMetadataContractValidator.Violation violation : result.violations()) {
            GradingTraceCollector.add(new GradingTraceItem(
                    violation.configurationError()
                            ? GradingTraceItem.KIND_TEACHER_CONFIG
                            : GradingTraceItem.KIND_METADATA_CHECK,
                    GradingTraceItem.STATUS_FAIL,
                    violation.configurationError()
                            ? "Cấu hình metadata Function không hợp lệ"
                            : "Metadata Function không khớp",
                    violation.message(),
                    null,
                    null,
                    "FUNCTION_CONTRACT",
                    violation.code(),
                    violation.configurationError() ? "REVIEW_CONFIG" : null,
                    null,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    violation.expected(),
                    violation.actual(),
                    "Metadata gate thất bại; bỏ qua toàn bộ test case và chấm 0 điểm."));
        }
        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_SUMMARY,
                GradingTraceItem.STATUS_FAIL,
                "Function không vượt qua metadata gate",
                buildMetadataGateError(result),
                null,
                null,
                "FUNCTION_CONTRACT",
                "GATE_FAILED",
                null,
                null,
                BigDecimal.ZERO,
                totalPoints,
                totalPoints,
                "Metadata contract hợp lệ",
                "Không hợp lệ",
                "Test case không được thực thi."));
    }

    private String buildMetadataGateError(
            FunctionMetadataContractValidator.ValidationResult result) {
        boolean configurationError = result.violations().stream()
                .anyMatch(FunctionMetadataContractValidator.Violation::configurationError);
        String prefix = configurationError
                ? "[LỖI CẤU HÌNH METADATA] "
                : "[METADATA FUNCTION KHÔNG HỢP LỆ] ";
        StringJoiner messages = new StringJoiner(" ");
        result.violations().forEach(violation -> messages.add(violation.message()));
        return prefix + messages;
    }

    private void setFailedSubmission(ExamSubmission submission, String message) {
        if (submission == null) {
            return;
        }
        submission.setScoreEarned(BigDecimal.ZERO);
        submission.setErrorMessage(message);
    }

    private boolean extractCaseSensitiveNames(String gradingRubricJson) {
        if (gradingRubricJson == null || gradingRubricJson.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(gradingRubricJson)
                    .path("grading_payload")
                    .path("grading_settings")
                    .path("case_sensitive_names")
                    .asBoolean(false);
        } catch (Exception e) {
            log.warn("Không thể đọc case_sensitive_names từ rubric: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Parses {@code grading_payload.routines[]} from the rubric JSON into
     * RoutineMetadata. Returns an empty list when the rubric is missing,
     * malformed, or omits the routines array — caller falls back to teacher
     * schema metadata in that case.
     */
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
}
