package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregate outcome of running all white-box rules against one SQL answer.
 *
 * @param violations      per-rule outcomes (PASS included for summary counts)
 * @param rawDeduction    sum of penalties for FAIL rules with DEDUCTION severity, before caps
 * @param cappedDeduction rawDeduction after applying the effective cap (the amount to subtract)
 * @param sqlParseOk      whether the SQL parsed (false => parser-dependent rules were UNVERIFIED)
 */
public record WhiteboxResult(
        List<WhiteboxViolation> violations,
        BigDecimal rawDeduction,
        BigDecimal cappedDeduction,
        boolean sqlParseOk) {

    public static WhiteboxResult empty() {
        return new WhiteboxResult(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true);
    }

    public boolean isEmpty() {
        return violations.isEmpty();
    }
}
