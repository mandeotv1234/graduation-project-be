package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Shared scoring controls from {@code grading_payload.whitebox_settings}.
 *
 * @param maxTotalDeduction    absolute cap on total white-box deduction (null = no absolute cap)
 * @param maxTotalDeductionPct percentage cap (of question points) (null = no percentage cap)
 * @param stopOnFirstViolation stop evaluating after the first FAIL when true
 */
public record WhiteboxSettings(
        BigDecimal maxTotalDeduction,
        BigDecimal maxTotalDeductionPct,
        boolean stopOnFirstViolation) {

    public static WhiteboxSettings defaults() {
        return new WhiteboxSettings(null, null, false);
    }

    /**
     * Effective cap on raw deduction, or {@code null} when both caps are unset (no cap).
     * When both an absolute and a percentage cap are present the lower (stricter) value wins.
     */
    public BigDecimal effectiveCap(BigDecimal questionPoints) {
        BigDecimal pctCap = null;
        if (maxTotalDeductionPct != null && questionPoints != null) {
            pctCap = questionPoints
                    .multiply(maxTotalDeductionPct)
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        }
        if (maxTotalDeduction == null) {
            return pctCap;
        }
        if (pctCap == null) {
            return maxTotalDeduction;
        }
        return maxTotalDeduction.min(pctCap);
    }
}
