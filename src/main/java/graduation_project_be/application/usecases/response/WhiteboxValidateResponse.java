package graduation_project_be.application.usecases.response;

import graduation_project_be.application.usecases.grading.whitebox.WhiteboxResult;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxStatus;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxViolation;

import java.math.BigDecimal;
import java.util.List;

/**
 * White-box validation result for preview / model-answer checking. Carries the full per-rule trace
 * (teacher view) and the deduction; the frontend shows model-answer violations as warnings only.
 */
public record WhiteboxValidateResponse(
        boolean sqlParseOk,
        double rawDeduction,
        double cappedDeduction,
        int passCount,
        int failCount,
        int warnCount,
        int unverifiedCount,
        List<ViolationView> violations) {

    public record ViolationView(
            String ruleId,
            String status,
            String label,
            String reason,
            String expected,
            String actual,
            Double configuredPenalty,
            double deductedPoints) {
    }

    public static WhiteboxValidateResponse fromResult(WhiteboxResult result) {
        int pass = 0;
        int fail = 0;
        int warn = 0;
        int unverified = 0;
        List<ViolationView> views = new java.util.ArrayList<>();
        for (WhiteboxViolation v : result.violations()) {
            switch (v.status()) {
                case PASS -> pass++;
                case FAIL -> fail++;
                case WARN -> warn++;
                case UNVERIFIED -> unverified++;
            }
            views.add(new ViolationView(
                    v.ruleId(),
                    v.status().name(),
                    v.label(),
                    v.reason(),
                    v.expected(),
                    v.actual(),
                    v.configuredPenalty() == null ? null : v.configuredPenalty().doubleValue(),
                    v.deductedPoints() == null ? 0d : v.deductedPoints().doubleValue()));
        }
        return new WhiteboxValidateResponse(
                result.sqlParseOk(),
                toDouble(result.rawDeduction()),
                toDouble(result.cappedDeduction()),
                pass, fail, warn, unverified, views);
    }

    private static double toDouble(BigDecimal value) {
        return value == null ? 0d : value.doubleValue();
    }
}
