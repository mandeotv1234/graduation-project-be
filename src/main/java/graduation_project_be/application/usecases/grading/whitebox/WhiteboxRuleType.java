package graduation_project_be.application.usecases.grading.whitebox;

/**
 * How a white-box rule interprets a match against the student SQL.
 *
 * <ul>
 *   <li>{@code FORBIDDEN} — the construct must NOT appear; presence is a violation.</li>
 *   <li>{@code REQUIRED} — the construct MUST appear; absence is a violation.</li>
 *   <li>{@code LIMIT} — a numeric ceiling (e.g. max depth / max joins) must not be exceeded.</li>
 * </ul>
 */
public enum WhiteboxRuleType {
    FORBIDDEN,
    REQUIRED,
    LIMIT
}
