package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.TestCaseRepository;
import graduation_project_be.domain.models.TestCase;
import graduation_project_be.domain.models.enums.MatchType;
import graduation_project_be.domain.models.enums.VerificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Transforms a rubric JSON (as produced by Gemini for SP/Function/Trigger
 * questions) into persisted {@link TestCase} entities.
 *
 * <p>Pipeline (called from CreateExamQuestionsUsecase per T09):
 * <ol>
 *   <li>{@link #parse} — JSON → in-memory TestCase list (no DB writes yet).
 *   <li>Caller hands the list to {@link ExpectedValueDeriver} which fills in
 *       {@code expectedValue} for each TC.
 *   <li>{@link #persist} — saves the now-complete list.
 * </ol>
 *
 * <p>Why split parse + persist around the deriver: we MUST run derivation BEFORE
 * inserting rows because expectedValue is NOT NULL on the test_cases table
 * (per grad-changelog-GRAD-68). If derivation fails for any TC the question
 * shouldn't be saved at all.
 *
 * <p>Score weight handling: Gemini sends per-TC weights (typically 0..1). We
 * normalize the sum to 1.0 so that gradeByTestCases can multiply earnedTotal
 * by the question's totalPoints and get a stable scaling regardless of how
 * "generously" Gemini distributed weights.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RubricToTestCaseTransformer {

    private final ObjectMapper objectMapper;
    private final TestCaseRepository testCaseRepository;

    /**
     * @param questionId    Required to set on each TestCase.
     * @param rubricJson    JSON returned by GeminiService.generateGradingRubric.
     *                      Expected shape (top-level): grading_payload.test_cases[]
     *                      OR test_cases[] at root (some prompts skip the wrapper).
     * @return List of TestCase models with expectedValue still null. Caller
     *         must derive expectedValue before calling persist.
     */
    public List<TestCase> parse(Long questionId, String rubricJson) {
        if (rubricJson == null || rubricJson.isBlank()) {
            return List.of();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rubricJson);
        } catch (Exception e) {
            log.error("Cannot parse rubric JSON for Q{}: {}", questionId, e.getMessage());
            return List.of();
        }

        // Look for test_cases at grading_payload.test_cases first, then root.
        JsonNode testCasesNode = root.path("grading_payload").path("test_cases");
        if (!testCasesNode.isArray() || testCasesNode.isEmpty()) {
            testCasesNode = root.path("test_cases");
        }
        if (!testCasesNode.isArray()) {
            log.warn("No test_cases array found in rubric for Q{}", questionId);
            return List.of();
        }

        List<TestCase> result = new ArrayList<>();
        int order = 1;
        for (JsonNode tc : testCasesNode) {
            result.add(buildOne(questionId, tc, order++));
        }

        normalizeWeights(result);
        return result;
    }

    /**
     * Persists the test cases. Replaces any existing TC for the question so
     * regenerating rubric doesn't accumulate stale rows. expectedValue must be
     * non-null on every TC at this point.
     */
    public void persist(Long questionId, List<TestCase> testCases) {
        // Replace strategy: delete-then-insert. Simpler than diff-update and
        // there is no grading-in-progress race because rubric regeneration
        // happens at question-authoring time, not during grading.
        // NOTE: TestCaseRepository currently has no deleteByQuestionId — see
        //       T08-followup note in TASKS.md. For now we only INSERT and rely on
        //       the auto-trigger pipeline running on a question that has no
        //       existing test cases (CreateExamQuestionsUsecase path).
        for (TestCase tc : testCases) {
            if (tc.getExpectedValue() == null) {
                log.error("Refusing to persist Q{} TC{} — expectedValue is null. "
                        + "Did you forget to call ExpectedValueDeriver?", questionId, tc.getOrderIndex());
                throw new IllegalStateException("TestCase.expectedValue must be set before persist");
            }
            testCaseRepository.save(tc);
            log.info("[RubricToTestCase] Q{} TC{} ({}) persisted, weight={}",
                    questionId, tc.getOrderIndex(), tc.getCaseName(), tc.getScoreWeight());
        }
    }

    private TestCase buildOne(Long questionId, JsonNode tc, int orderIndex) {
        VerificationType vType = parseVerificationType(textOrNull(tc, "verification_type"));
        String validationQuery = textOrNull(tc, "validation_query");

        // For non-PRINT_OUTPUT types, validation_query is REQUIRED — it's the
        // SQL the engine runs to capture actual output. If AI omitted it,
        // grading would either fail at runtime or compare empty strings, so
        // bail early with a clear error instead of silently accepting bad data.
        // PRINT_OUTPUT is exempt: actual output comes from PRINT messages,
        // captured regardless of validation_query.
        if (validationQuery == null && vType != VerificationType.PRINT_OUTPUT) {
            throw new IllegalArgumentException(String.format(
                    "Q%d TC%d (%s): validation_query is required for verification_type=%s. "
                            + "AI must supply the SQL that reads the actual outcome.",
                    questionId, orderIndex, textOrNull(tc, "case_name"), vType));
        }

        return TestCase.builder()
                .questionId(questionId)
                .orderIndex(orderIndex)
                .caseName(textOrNull(tc, "case_name"))
                .verificationType(vType)
                .matchType(parseMatchType(textOrNull(tc, "match_type")))
                .setupScript(textOrNull(tc, "setup_script"))
                .invocationQuery(textOrNull(tc, "invocation_query"))
                .validationQuery(validationQuery)
                .inputParameters(jsonOrNull(tc.get("input_parameters")))
                // Raw weight from Gemini; normalized later.
                .scoreWeight(parseWeight(tc))
                // expectedValue: deliberately left null. ExpectedValueDeriver fills.
                .expectedValue(null)
                .build();
    }

    private VerificationType parseVerificationType(String s) {
        if (s == null) return VerificationType.RETURN_VALUE;
        try {
            return VerificationType.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("Unknown verification_type [{}], defaulting to RETURN_VALUE", s);
            return VerificationType.RETURN_VALUE;
        }
    }

    private MatchType parseMatchType(String s) {
        if (s == null) return MatchType.EXACT;
        try {
            return MatchType.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return MatchType.EXACT;
        }
    }

    private BigDecimal parseWeight(JsonNode tc) {
        // Prefer score_weight (new schema). Fall back to penalty_value (legacy).
        if (tc.has("score_weight") && tc.get("score_weight").isNumber()) {
            return BigDecimal.valueOf(tc.get("score_weight").asDouble());
        }
        if (tc.has("penalty_value") && tc.get("penalty_value").isNumber()) {
            return BigDecimal.valueOf(tc.get("penalty_value").asDouble());
        }
        return BigDecimal.ONE; // equal weight if AI didn't specify
    }

    private String textOrNull(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode child = n.get(field);
        if (child == null || child.isNull()) return null;
        String text = child.isTextual() ? child.asText() : child.toString();
        return text.isBlank() ? null : text;
    }

    private String jsonOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Rescales score_weight values so that the sum is exactly 1.0. Engine
     * gradeByTestCases multiplies the cumulative earned weight by question
     * totalPoints, so a normalized sum keeps grading sensible regardless of
     * what Gemini chose.
     *
     * <p>If all weights are zero/missing, distribute equally.
     */
    private void normalizeWeights(List<TestCase> testCases) {
        if (testCases.isEmpty()) return;

        BigDecimal sum = BigDecimal.ZERO;
        for (TestCase tc : testCases) {
            if (tc.getScoreWeight() != null) {
                sum = sum.add(tc.getScoreWeight().abs());
            }
        }

        if (sum.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal equal = BigDecimal.ONE.divide(
                    BigDecimal.valueOf(testCases.size()), 6, RoundingMode.HALF_UP);
            for (TestCase tc : testCases) {
                tc.setScoreWeight(equal);
            }
            return;
        }

        for (TestCase tc : testCases) {
            BigDecimal w = tc.getScoreWeight() != null ? tc.getScoreWeight().abs() : BigDecimal.ZERO;
            tc.setScoreWeight(w.divide(sum, 6, RoundingMode.HALF_UP));
        }
    }
}
