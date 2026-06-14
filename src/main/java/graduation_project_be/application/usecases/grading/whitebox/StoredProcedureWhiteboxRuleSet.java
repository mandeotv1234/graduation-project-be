package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static graduation_project_be.application.usecases.grading.whitebox.RoutineWhiteboxPatterns.*;

/**
 * White-box rule catalog for STORED_PROCEDURE question type (12 rules).
 * All rules use {@code parserRequired=false} — text-scan only, no JSQLParser dependency.
 */
final class StoredProcedureWhiteboxRuleSet {

    static final String QUESTION_TYPE = "STORED_PROCEDURE";
    private static final WhiteboxPenaltyUnit PCT = WhiteboxPenaltyUnit.PERCENTAGE_OF_QUESTION;

    private StoredProcedureWhiteboxRuleSet() {
    }

    static void registerInto(Map<String, WhiteboxCatalogEntry> entries,
                             Map<String, WhiteboxRuleEvaluator> evaluators) {

        // ---- Group: Error handling ----
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

        // ---- Group: Transaction ----
        reg(entries, evaluators, "SP_REQUIRED_TRANSACTION", WhiteboxRuleType.REQUIRED, "TRANSACTION",
                "Bắt buộc Transaction", "Stored procedure phải dùng BEGIN TRAN kết hợp COMMIT hoặc ROLLBACK.",
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

        // ---- Group: Settings ----
        reg(entries, evaluators, "SP_REQUIRED_SET_NOCOUNT_ON", WhiteboxRuleType.REQUIRED, "SETTINGS",
                "Bắt buộc SET NOCOUNT ON", "Stored procedure nên khai báo SET NOCOUNT ON để tắt thông báo số dòng.",
                5, false,
                WhiteboxFeature.SET_NOCOUNT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), SET_NOCOUNT_ON),
                        "Không tìm thấy SET NOCOUNT ON"));

        // ---- Group: Validation ----
        reg(entries, evaluators, "SP_REQUIRED_INPUT_VALIDATION", WhiteboxRuleType.REQUIRED, "VALIDATION",
                "Bắt buộc kiểm tra tham số đầu vào", "Stored procedure phải kiểm tra tham số NULL trước khi xử lý.",
                10, false,
                WhiteboxFeature.INPUT_VALIDATION, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), INPUT_VALIDATION),
                        "Không tìm thấy kiểm tra NULL cho tham số đầu vào"));

        // ---- Group: Parameters ----
        reg(entries, evaluators, "SP_REQUIRED_OUTPUT_PARAMETER", WhiteboxRuleType.REQUIRED, "PARAMETERS",
                "Bắt buộc tham số OUTPUT", "Stored procedure phải khai báo ít nhất một tham số OUTPUT/OUT.",
                15, false,
                WhiteboxFeature.OUTPUT_PARAM, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), OUTPUT_PARAM),
                        "Không tìm thấy tham số OUTPUT"));

        reg(entries, evaluators, "SP_MAX_PARAM_COUNT", WhiteboxRuleType.LIMIT, "PARAMETERS",
                "Giới hạn số lượng tham số", "Số tham số đầu vào không được vượt quá max_params.",
                5, false,
                WhiteboxFeature.PARAM_COUNT, WhiteboxFeatureKind.NUMERIC, WhiteboxPolicy.AT_MOST,
                List.of(), List.of(WhiteboxParamSpec.number("max_params", "Số tham số tối đa", true, 5)),
                (ctx, rule) -> {
                    int max = WhiteboxParams.intParam(rule, "max_params", 5);
                    int actual = countParameters(ctx.cleanedSql());
                    return WhiteboxEvaluation.of(actual > max,
                            "Số tham số: " + actual + (actual > max ? " > " : " <= ") + max);
                });

        // ---- Group: Forbidden ----
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

        reg(entries, evaluators, "SP_FORBIDDEN_PRINT", WhiteboxRuleType.FORBIDDEN, "DEBUG",
                "Cấm PRINT", "Cấm dùng câu lệnh PRINT (chỉ dùng để debug, không nên có trong code production).",
                5, false,
                WhiteboxFeature.PRINT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), PRINT_STMT),
                        "Phát hiện câu lệnh PRINT"));

        reg(entries, evaluators, "SP_FORBIDDEN_RAISERROR_LEGACY", WhiteboxRuleType.FORBIDDEN, "ERROR_HANDLING",
                "Cấm RAISERROR cũ", "Cấm dùng RAISERROR (cú pháp cũ) — nên dùng THROW thay thế.",
                5, false,
                WhiteboxFeature.RAISERROR, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), RAISERROR_LEGACY),
                        "Phát hiện RAISERROR (cú pháp cũ, nên dùng THROW)"));
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
