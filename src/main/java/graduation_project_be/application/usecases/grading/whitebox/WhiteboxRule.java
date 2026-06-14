package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * One configured white-box rule from {@code grading_payload.whitebox_rules[]}.
 *
 * @param ruleId       catalog id (UPPER_SNAKE_CASE), resolves the evaluator
 * @param enabled      teacher can disable without removing config
 * @param type         FORBIDDEN / REQUIRED / LIMIT (interpretation of a match)
 * @param penaltyValue points (ABSOLUTE) or percent (PERCENTAGE_OF_QUESTION) deducted on FAIL
 * @param penaltyUnit  ABSOLUTE or PERCENTAGE_OF_QUESTION
 * @param severity     DEDUCTION or WARNING_ONLY
 * @param description  shown in trace / UI (catalog default, teacher-editable)
 * @param params       rule-specific parameters (max_depth, keywords[], functions[], operators[], max_joins)
 */
public record WhiteboxRule(
        String ruleId,
        boolean enabled,
        WhiteboxRuleType type,
        BigDecimal penaltyValue,
        WhiteboxPenaltyUnit penaltyUnit,
        WhiteboxSeverity severity,
        String description,
        JsonNode params) {

    public BigDecimal penaltyValueOrZero() {
        return penaltyValue == null ? BigDecimal.ZERO : penaltyValue;
    }
}
