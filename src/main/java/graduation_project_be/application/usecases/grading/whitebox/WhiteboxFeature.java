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
    KEYWORD("Từ khóa cụ thể"),
    // FUNCTION question type features
    RETURN_STMT("Câu lệnh RETURN"),
    SCALAR_FUNCTION("Hàm vô hướng (scalar)"),
    TABLE_VALUED_FUNCTION("Hàm trả về bảng (TVF)"),
    RETURN_TYPE("Kiểu trả về"),
    SCHEMABINDING("WITH SCHEMABINDING"),
    NONDETERMINISTIC("Hàm không tất định"),
    DML_IN_FUNCTION("DML trong hàm"),
    CURSOR("CURSOR"),
    DYNAMIC_SQL("SQL động"),
    // STORED_PROCEDURE question type features
    TRY_CATCH("TRY/CATCH"),
    TRANSACTION("Transaction"),
    SET_NOCOUNT("SET NOCOUNT ON"),
    INPUT_VALIDATION("Kiểm tra tham số đầu vào"),
    OUTPUT_PARAM("Tham số OUTPUT"),
    DDL_IN_PROC("DDL trong stored procedure"),
    TRUNCATE("TRUNCATE TABLE"),
    PRINT("PRINT"),
    RAISERROR("RAISERROR"),
    PARAM_COUNT("Số lượng tham số"),
    // INSERT_DATA question type features
    NOCHECK_CONSTRAINT("NOCHECK CONSTRAINT"),
    IDENTITY_INSERT("IDENTITY_INSERT"),
    DISABLE_TRIGGER("DISABLE TRIGGER"),
    INSERT_COLUMN_LIST("Danh sách cột INSERT"),
    INSERT_SELECT("INSERT ... SELECT"),
    UPDATE_DELETE("UPDATE/DELETE"),
    MERGE("MERGE"),
    STATEMENT_COUNT("Số câu lệnh");

    private final String label;

    WhiteboxFeature(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
