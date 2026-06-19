package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static graduation_project_be.application.usecases.grading.whitebox.InsertDataWhiteboxPatterns.*;

/**
 * White-box rule catalog for INSERT_DATA question type. The checks are text-scan only and evaluate
 * the student's submitted script, not the system FK fallback SQL used during black-box grading.
 */
final class InsertDataWhiteboxRuleSet {

    static final String QUESTION_TYPE = "INSERT_DATA";
    private static final WhiteboxPenaltyUnit PCT = WhiteboxPenaltyUnit.PERCENTAGE_OF_QUESTION;

    private InsertDataWhiteboxRuleSet() {
    }

    static void registerInto(Map<String, WhiteboxCatalogEntry> entries,
                             Map<String, WhiteboxRuleEvaluator> evaluators) {
        reg(entries, evaluators, "FORBIDDEN_NOCHECK_CONSTRAINT", WhiteboxRuleType.FORBIDDEN, "CONSTRAINT_SAFETY",
                "Cấm tự tắt kiểm tra ràng buộc", "Cấm dùng NOCHECK CONSTRAINT trong bài làm INSERT DATA.",
                25, WhiteboxFeature.NOCHECK_CONSTRAINT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> match(ctx.cleanedSql(), NOCHECK_CONSTRAINT,
                        "Phát hiện NOCHECK CONSTRAINT trong script sinh viên"));

        reg(entries, evaluators, "FORBIDDEN_IDENTITY_INSERT", WhiteboxRuleType.FORBIDDEN, "CONSTRAINT_SAFETY",
                "Cấm SET IDENTITY_INSERT ON", "Cấm bật IDENTITY_INSERT để chèn trực tiếp giá trị identity.",
                20, WhiteboxFeature.IDENTITY_INSERT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> match(ctx.cleanedSql(), SET_IDENTITY_INSERT_ON,
                        "Phát hiện SET IDENTITY_INSERT ... ON"));

        reg(entries, evaluators, "FORBIDDEN_DISABLE_TRIGGER", WhiteboxRuleType.FORBIDDEN, "CONSTRAINT_SAFETY",
                "Cấm DISABLE TRIGGER", "Cấm tắt trigger để né logic kiểm tra dữ liệu.",
                20, WhiteboxFeature.DISABLE_TRIGGER, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> match(ctx.cleanedSql(), DISABLE_TRIGGER,
                        "Phát hiện DISABLE TRIGGER"));

        reg(entries, evaluators, "REQUIRED_COLUMN_LIST", WhiteboxRuleType.REQUIRED, "INSERT_STYLE",
                "Bắt buộc ghi danh sách cột", "Mọi INSERT INTO phải khai báo danh sách cột tường minh.",
                10, WhiteboxFeature.INSERT_COLUMN_LIST, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> {
                    InsertDataWhiteboxPatterns.ColumnListCheck check = columnListCheck(ctx.cleanedSql());
                    if (check.insertCount() == 0) {
                        return WhiteboxEvaluation.fail("Không tìm thấy INSERT INTO trong script");
                    }
                    boolean violated = check.missingCount() > 0;
                    return WhiteboxEvaluation.of(violated,
                            "Có " + check.missingCount() + "/" + check.insertCount()
                                    + " lệnh INSERT thiếu danh sách cột"
                                    + (check.firstMissing() == null ? "" : ": " + check.firstMissing()));
                });

        reg(entries, evaluators, "FORBIDDEN_INSERT_SELECT", WhiteboxRuleType.FORBIDDEN, "INSERT_STYLE",
                "Cấm INSERT ... SELECT", "Cấm sao chép dữ liệu bằng INSERT INTO ... SELECT.",
                15, WhiteboxFeature.INSERT_SELECT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> match(ctx.cleanedSql(), INSERT_SELECT,
                        "Phát hiện INSERT INTO ... SELECT"));

        reg(entries, evaluators, "FORBIDDEN_TRUNCATE", WhiteboxRuleType.FORBIDDEN, "DML_SAFETY",
                "Cấm TRUNCATE TABLE", "Cấm xóa toàn bộ dữ liệu bằng TRUNCATE TABLE trong câu INSERT DATA.",
                25, WhiteboxFeature.TRUNCATE, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> match(ctx.cleanedSql(), TRUNCATE_TABLE,
                        "Phát hiện TRUNCATE TABLE"));

        reg(entries, evaluators, "FORBIDDEN_UPDATE_DELETE", WhiteboxRuleType.FORBIDDEN, "DML_SAFETY",
                "Cấm UPDATE/DELETE", "Cấm sửa hoặc xóa dữ liệu có sẵn trong câu hỏi INSERT DATA.",
                25, WhiteboxFeature.UPDATE_DELETE, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> match(ctx.cleanedSql(), UPDATE_DELETE,
                        "Phát hiện UPDATE hoặc DELETE"));

        reg(entries, evaluators, "FORBIDDEN_MERGE", WhiteboxRuleType.FORBIDDEN, "DML_SAFETY",
                "Cấm MERGE", "Cấm dùng MERGE để thay thế logic INSERT được yêu cầu.",
                20, WhiteboxFeature.MERGE, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> match(ctx.cleanedSql(), MERGE,
                        "Phát hiện MERGE"));

        reg(entries, evaluators, "MAX_STATEMENTS", WhiteboxRuleType.LIMIT, "STATEMENT_LIMIT",
                "Giới hạn số câu lệnh", "Số câu lệnh thay đổi dữ liệu/cấu hình không vượt quá max_statements.",
                10, WhiteboxFeature.STATEMENT_COUNT, WhiteboxFeatureKind.NUMERIC, WhiteboxPolicy.AT_MOST,
                List.of(), List.of(WhiteboxParamSpec.number("max_statements", "Số câu lệnh tối đa", true, 5)),
                (ctx, rule) -> {
                    int max = WhiteboxParams.intParam(rule, "max_statements", 5);
                    int actual = count(ctx.cleanedSql(), STATEMENT_START);
                    return WhiteboxEvaluation.of(actual > max,
                            "Số câu lệnh phát hiện = " + actual + " > " + max);
                });
    }

    private static WhiteboxEvaluation match(String sql, Pattern pattern, String fallback) {
        String hit = firstMatch(sql, pattern);
        return WhiteboxEvaluation.of(hit != null, hit == null ? fallback : hit);
    }

    private static void reg(Map<String, WhiteboxCatalogEntry> entries,
                            Map<String, WhiteboxRuleEvaluator> evaluators,
                            String ruleId, WhiteboxRuleType type, String group, String label,
                            String description, double defaultPenaltyPct,
                            WhiteboxFeature feature, WhiteboxFeatureKind featureKind,
                            WhiteboxPolicy policy, List<String> conflictsWith,
                            List<WhiteboxParamSpec> params, WhiteboxRuleEvaluator evaluator) {
        entries.put(ruleId, new WhiteboxCatalogEntry(
                ruleId, type, group, label, description, WhiteboxSeverity.WARNING_ONLY,
                BigDecimal.valueOf(defaultPenaltyPct), PCT, false,
                List.of(QUESTION_TYPE), params,
                feature.name(), feature.label(), featureKind, policy, policy.label(), conflictsWith));
        evaluators.put(ruleId, evaluator);
    }
}
