package graduation_project_be.application.usecases.grading.whitebox;

/**
 * White-box rule severity (v1). {@code FAIL_ALL} is intentionally absent.
 *
 * <ul>
 *   <li>{@code DEDUCTION} — a violation reduces the score by the configured penalty.</li>
 *   <li>{@code WARNING_ONLY} — a violation records a WARN trace but never changes score/isCorrect.</li>
 * </ul>
 */
public enum WhiteboxSeverity {
    DEDUCTION,
    WARNING_ONLY
}
