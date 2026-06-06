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

/** Grades TRIGGER questions (falls back to routine grading for the trigger body). */
@Slf4j
@RequiredArgsConstructor
public class TriggerQuestionGrader {

    private final ExamSchemaService examSchemaService;
    private final TestCaseRepository testCaseRepository;
    private final GradingSupport support;
    private final RoutineQuestionGrader routineGrader;

    public boolean gradeTriggerAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission) {
        // T11/T12: setup_script applied per-test-case inside a transaction.
        // See note in gradeRoutineAlgorithmic — same reasoning applies here.

        java.util.List<TriggerMetadata> expectedTriggers = examSchemaService.extractTriggerMetadata(teacherSchemaName);
        java.util.List<TriggerMetadata> actualTriggers = examSchemaService.extractTriggerMetadata(schemaName);

        if (expectedTriggers == null || expectedTriggers.isEmpty()) {
            return support.gradeByTestCases(schemaName, teacherSchemaName, question, submission);
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
            testCasesPassed = support.gradeByTestCases(schemaName, teacherSchemaName, question, submission);
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
}
