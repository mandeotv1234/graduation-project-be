package graduation_project_be.application.usecases.grading.whitebox;

/**
 * A SQL construct a teacher reasons about when authoring white-box rules. The enum name is the stable
 * {@code featureId} the frontend groups by; the label is the Vietnamese display name. Several catalog
 * entries (different policies) can share one feature — e.g. {@code SUBQUERY} carries both FORBID
 * ({@code FORBIDDEN_SUBQUERY}) and AT_MOST ({@code MAX_SUBQUERY_DEPTH}).
 */
public enum WhiteboxFeature {
    SUBQUERY("Truy vấn con"),
    CORRELATED_SUBQUERY("Truy vấn con tương quan"),
    CTE("CTE (WITH)"),
    JOIN("JOIN"),
    LEFT_JOIN("LEFT JOIN"),
    INNER_JOIN("INNER JOIN"),
    CROSS_JOIN("CROSS JOIN"),
    OLD_JOIN_SYNTAX("Comma-join kiểu cũ"),
    SELECT_STAR("SELECT *"),
    DISTINCT("DISTINCT"),
    GROUP_BY("GROUP BY"),
    HAVING("HAVING"),
    AGGREGATE_FUNCTION("Hàm tổng hợp"),
    ORDER_BY("ORDER BY"),
    WINDOW_FUNCTION("Window function"),
    SET_OPERATOR("Toán tử tập hợp"),
    FUNCTION("Hàm cụ thể"),
    KEYWORD("Từ khóa cụ thể");

    private final String label;

    WhiteboxFeature(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
