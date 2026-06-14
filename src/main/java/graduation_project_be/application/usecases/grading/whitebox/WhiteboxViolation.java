package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;

/**
 * Result of one evaluated white-box rule.
 *
 * @param ruleId            catalog rule id
 * @param status            PASS / FAIL / WARN / UNVERIFIED
 * @param label             human label (teacher + student facing)
 * @param expected          expected method (teacher trace only)
 * @param actual            matched SQL context / evidence (teacher trace only)
 * @param reason            short reason for the status (student-safe)
 * @param configuredPenalty the configured penalty for this rule
 * @param deductedPoints    points actually deducted (0 unless FAIL with DEDUCTION severity)
 */
public record WhiteboxViolation(
        String ruleId,
        WhiteboxStatus status,
        String label,
        String expected,
        String actual,
        String reason,
        BigDecimal configuredPenalty,
        BigDecimal deductedPoints) {

    public boolean isDetailed() {
        return status != WhiteboxStatus.PASS;
    }
}
