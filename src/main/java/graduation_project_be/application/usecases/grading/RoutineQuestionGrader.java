package graduation_project_be.application.usecases.grading;

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

        // If teacher schema has no routines (DDL-only), grade purely by test cases
        // (100% weight)
        if (expectedRoutines == null || expectedRoutines.isEmpty()) {
            if (!hasTestCases) {
                // Nothing to grade against
                String message = "[THIẾU CẤU HÌNH] Câu hỏi không có metadata routine trong rubric/teacher schema "
                        + "và cũng không có test case.";
                log.warn("Câu {} không có metadata của routine và cũng không có test case", question.getId());
                support.addTeacherConfigTrace(
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
            boolean passed = support.gradeByTestCases(schemaName, teacherSchemaName, question, submission);
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
            testCasesPassed = support.gradeByTestCases(schemaName, teacherSchemaName, question, submission);
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
}
