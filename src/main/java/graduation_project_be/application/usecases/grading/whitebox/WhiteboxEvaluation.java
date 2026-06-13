package graduation_project_be.application.usecases.grading.whitebox;

/**
 * Raw evaluator verdict for one rule against one answer, before severity/penalty/cap logic.
 *
 * @param violated true when the rule's condition is broken (FORBIDDEN present / REQUIRED absent /
 *                 LIMIT exceeded)
 * @param actual   matched SQL evidence for the trace (teacher-only); null when not applicable
 */
public record WhiteboxEvaluation(boolean violated, String actual) {

    public static WhiteboxEvaluation pass() {
        return new WhiteboxEvaluation(false, null);
    }

    public static WhiteboxEvaluation fail(String actual) {
        return new WhiteboxEvaluation(true, actual);
    }

    public static WhiteboxEvaluation of(boolean violated, String actual) {
        return new WhiteboxEvaluation(violated, violated ? actual : null);
    }
}
