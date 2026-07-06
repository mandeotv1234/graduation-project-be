package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static graduation_project_be.application.usecases.grading.whitebox.RoutineWhiteboxPatterns.*;

/**
 * Danh mục rule white-box cho loại câu STORED_PROCEDURE.
 * Tất cả rule dùng {@code parserRequired=false}: chỉ quét text, không phụ thuộc JSQLParser.
 */
final class StoredProcedureWhiteboxRuleSet {

    static final String QUESTION_TYPE = "STORED_PROCEDURE";
    private static final WhiteboxPenaltyUnit PCT = WhiteboxPenaltyUnit.PERCENTAGE_OF_QUESTION;

    private StoredProcedureWhiteboxRuleSet() {
    }

    static void registerInto(Map<String, WhiteboxCatalogEntry> entries,
                             Map<String, WhiteboxRuleEvaluator> evaluators) {

        // ---- Nhóm: Xử lý lỗi ----
        reg(entries, evaluators, "SP_REQUIRED_TRY_CATCH", WhiteboxRuleType.REQUIRED, "ERROR_HANDLING",
                "Bắt buộc TRY/CATCH", "Stored procedure phải có khối BEGIN TRY ... BEGIN CATCH để xử lý lỗi.",
                15, false,
                WhiteboxFeature.TRY_CATCH, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> {
                    String sql = ctx.cleanedSql();
                    boolean hasTry = find(sql, BEGIN_TRY);
                    boolean hasCatch = find(sql, BEGIN_CATCH);
                    boolean violated = !hasTry || !hasCatch;
                    String actual = !hasTry ? "Thiếu BEGIN TRY" : "Thiếu BEGIN CATCH";
                    return WhiteboxEvaluation.of(violated, violated ? actual : "Có đầy đủ BEGIN TRY/CATCH");
                });

        // ---- Nhóm: Transaction ----
        reg(entries, evaluators, "SP_REQUIRED_TRANSACTION", WhiteboxRuleType.REQUIRED, "TRANSACTION",
                "Bắt buộc transaction", "Stored procedure phải dùng BEGIN TRAN kết hợp COMMIT hoặc ROLLBACK.",
                20, false,
                WhiteboxFeature.TRANSACTION, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> {
                    String sql = ctx.cleanedSql();
                    boolean hasTran = find(sql, BEGIN_TRAN);
                    boolean hasCommitOrRollback = find(sql, COMMIT) || find(sql, ROLLBACK);
                    boolean violated = !hasTran || !hasCommitOrRollback;
                    String actual = !hasTran ? "Thiếu BEGIN TRAN" : "Thiếu COMMIT/ROLLBACK";
                    return WhiteboxEvaluation.of(violated, violated ? actual : "Có đầy đủ transaction");
                });

        // ---- Nhóm: Cấm dùng ----
        reg(entries, evaluators, "SP_FORBIDDEN_CURSOR", WhiteboxRuleType.FORBIDDEN, "CURSOR",
                "Cấm CURSOR", "Cấm khai báo và dùng CURSOR trong stored procedure.",
                20, false,
                WhiteboxFeature.CURSOR, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), CURSOR_DECLARE),
                        "Phát hiện DECLARE CURSOR"));

        reg(entries, evaluators, "SP_FORBIDDEN_DYNAMIC_SQL", WhiteboxRuleType.FORBIDDEN, "DYNAMIC_SQL",
                "Cấm SQL động", "Cấm dùng EXEC() hoặc sp_executesql trong stored procedure.",
                25, false,
                WhiteboxFeature.DYNAMIC_SQL, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), DYNAMIC_SQL),
                        "Phát hiện SQL động (EXEC() hoặc sp_executesql)"));

        reg(entries, evaluators, "SP_FORBIDDEN_DDL_IN_PROC", WhiteboxRuleType.FORBIDDEN, "DDL",
                "Cấm DDL trong stored procedure", "Cấm dùng DROP TABLE, CREATE TABLE hoặc ALTER TABLE.",
                20, false,
                WhiteboxFeature.DDL_IN_PROC, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), DDL_IN_PROC),
                        "Phát hiện câu lệnh DDL (DROP/CREATE/ALTER TABLE)"));

        reg(entries, evaluators, "SP_FORBIDDEN_TRUNCATE", WhiteboxRuleType.FORBIDDEN, "DDL",
                "Cấm TRUNCATE TABLE", "Cấm dùng TRUNCATE TABLE trong stored procedure.",
                15, false,
                WhiteboxFeature.TRUNCATE, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), TRUNCATE_TABLE),
                        "Phát hiện TRUNCATE TABLE"));
    }

    private static void reg(Map<String, WhiteboxCatalogEntry> entries,
                            Map<String, WhiteboxRuleEvaluator> evaluators,
                            String ruleId, WhiteboxRuleType type, String group, String label,
                            String description, double defaultPenaltyPct, boolean parserRequired,
                            WhiteboxFeature feature, WhiteboxFeatureKind featureKind,
                            WhiteboxPolicy policy, List<String> conflictsWith,
                            List<WhiteboxParamSpec> params, WhiteboxRuleEvaluator evaluator) {
        entries.put(ruleId, new WhiteboxCatalogEntry(
                ruleId, type, group, label, description, WhiteboxSeverity.WARNING_ONLY,
                BigDecimal.valueOf(defaultPenaltyPct), PCT, parserRequired,
                List.of(QUESTION_TYPE), params,
                feature.name(), feature.label(), featureKind, policy, policy.label(), conflictsWith));
        evaluators.put(ruleId, evaluator);
    }
}
