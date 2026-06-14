package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static graduation_project_be.application.usecases.grading.whitebox.RoutineWhiteboxPatterns.*;

/**
 * White-box rule catalog for FUNCTION question type (9 rules).
 * All rules use {@code parserRequired=false} — text-scan only, no JSQLParser dependency.
 */
final class FunctionWhiteboxRuleSet {

    static final String QUESTION_TYPE = "FUNCTION";
    private static final WhiteboxPenaltyUnit PCT = WhiteboxPenaltyUnit.PERCENTAGE_OF_QUESTION;

    private FunctionWhiteboxRuleSet() {
    }

    static void registerInto(Map<String, WhiteboxCatalogEntry> entries,
                             Map<String, WhiteboxRuleEvaluator> evaluators) {

        // ---- Group: Return ----
        reg(entries, evaluators, "REQUIRED_RETURN", WhiteboxRuleType.REQUIRED, "RETURN",
                "Bắt buộc có câu lệnh RETURN", "Hàm phải có câu lệnh RETURN để trả về giá trị.",
                15, false,
                WhiteboxFeature.RETURN_STMT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), RETURN_STMT),
                        "Không tìm thấy câu lệnh RETURN"));

        reg(entries, evaluators, "REQUIRED_SCALAR_FUNCTION", WhiteboxRuleType.REQUIRED, "RETURN",
                "Bắt buộc là hàm vô hướng (scalar)", "Hàm phải khai báo kiểu trả về vô hướng (RETURNS <type>, không phải TABLE).",
                20, false,
                WhiteboxFeature.SCALAR_FUNCTION, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of("REQUIRED_TABLE_VALUED_FUNCTION"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), RETURNS_SCALAR),
                        "Không tìm thấy khai báo RETURNS kiểu vô hướng"));

        reg(entries, evaluators, "REQUIRED_TABLE_VALUED_FUNCTION", WhiteboxRuleType.REQUIRED, "RETURN",
                "Bắt buộc là hàm trả về bảng (TVF)", "Hàm phải khai báo RETURNS TABLE.",
                20, false,
                WhiteboxFeature.TABLE_VALUED_FUNCTION, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of("REQUIRED_SCALAR_FUNCTION"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), RETURNS_TABLE),
                        "Không tìm thấy khai báo RETURNS TABLE"));

        reg(entries, evaluators, "REQUIRED_RETURN_TYPE", WhiteboxRuleType.REQUIRED, "RETURN",
                "Kiểm tra kiểu trả về", "Kiểu trả về sau RETURNS phải khớp với kiểu kỳ vọng.",
                10, false,
                WhiteboxFeature.RETURN_TYPE, WhiteboxFeatureKind.COMPOSITE, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(WhiteboxParamSpec.string("expected_type", "Kiểu trả về kỳ vọng", true)),
                (ctx, rule) -> {
                    String expected = WhiteboxParams.stringParam(rule, "expected_type", "");
                    if (expected.isBlank()) {
                        return WhiteboxEvaluation.of(false, "Không cấu hình expected_type");
                    }
                    String actual = extractReturnType(ctx.cleanedSql());
                    boolean violated = !actual.equalsIgnoreCase(expected.trim());
                    return WhiteboxEvaluation.of(violated,
                            violated ? "Kiểu trả về: thực tế='" + actual + "', kỳ vọng='" + expected + "'"
                                     : "Kiểu trả về khớp: " + actual);
                });

        // ---- Group: Options ----
        reg(entries, evaluators, "REQUIRED_SCHEMABINDING", WhiteboxRuleType.REQUIRED, "OPTIONS",
                "Bắt buộc WITH SCHEMABINDING", "Hàm phải khai báo WITH SCHEMABINDING để ràng buộc schema.",
                5, false,
                WhiteboxFeature.SCHEMABINDING, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), SCHEMABINDING),
                        "Không tìm thấy WITH SCHEMABINDING"));

        // ---- Group: Forbidden ----
        reg(entries, evaluators, "FORBIDDEN_NONDETERMINISTIC_FN", WhiteboxRuleType.FORBIDDEN, "DETERMINISM",
                "Cấm hàm không tất định", "Cấm dùng GETDATE, SYSDATETIME, GETUTCDATE, RAND, NEWID trong hàm.",
                10, false,
                WhiteboxFeature.NONDETERMINISTIC, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), NONDETERMINISTIC_FN),
                        "Phát hiện hàm không tất định (GETDATE/RAND/NEWID...)"));

        reg(entries, evaluators, "FORBIDDEN_DML_IN_FUNCTION", WhiteboxRuleType.FORBIDDEN, "DML",
                "Cấm DML trong hàm", "Hàm không được chứa INSERT, UPDATE hoặc DELETE.",
                20, false,
                WhiteboxFeature.DML_IN_FUNCTION, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), DML_IN_FUNCTION),
                        "Phát hiện câu lệnh DML (INSERT/UPDATE/DELETE) trong hàm"));

        reg(entries, evaluators, "FORBIDDEN_CURSOR", WhiteboxRuleType.FORBIDDEN, "CURSOR",
                "Cấm CURSOR", "Cấm khai báo và dùng CURSOR.",
                20, false,
                WhiteboxFeature.CURSOR, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), CURSOR_DECLARE),
                        "Phát hiện DECLARE CURSOR"));

        reg(entries, evaluators, "FORBIDDEN_DYNAMIC_SQL", WhiteboxRuleType.FORBIDDEN, "DYNAMIC_SQL",
                "Cấm SQL động", "Cấm dùng EXEC() hoặc sp_executesql.",
                25, false,
                WhiteboxFeature.DYNAMIC_SQL, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), DYNAMIC_SQL),
                        "Phát hiện SQL động (EXEC() hoặc sp_executesql)"));
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
