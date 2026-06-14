package graduation_project_be.application.usecases.grading.whitebox;

import graduation_project_be.domain.models.GradingTraceItem;

/**
 * Outcome of evaluating one white-box rule against a SQL answer.
 *
 * <ul>
 *   <li>{@code PASS} — rule satisfied; summarised only, not deducted.</li>
 *   <li>{@code FAIL} — rule violated with severity DEDUCTION; deducts the penalty.</li>
 *   <li>{@code WARN} — rule violated with severity WARNING_ONLY; deducts nothing.</li>
 *   <li>{@code UNVERIFIED} — parser-dependent rule whose SQL did not parse; deducts nothing, not a pass.</li>
 * </ul>
 */
public enum WhiteboxStatus {
    PASS(GradingTraceItem.STATUS_PASS),
    FAIL(GradingTraceItem.STATUS_FAIL),
    WARN(GradingTraceItem.STATUS_WARN),
    UNVERIFIED(GradingTraceItem.STATUS_UNVERIFIED);

    private final String traceStatus;

    WhiteboxStatus(String traceStatus) {
        this.traceStatus = traceStatus;
    }

    /** The matching {@link GradingTraceItem} status string written to the grading trace. */
    public String traceStatus() {
        return traceStatus;
    }
}
