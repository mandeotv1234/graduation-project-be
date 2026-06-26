package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** White-box rule catalog for CREATE_TABLE (13 rules from whitebox-testing-plan.html). */
final class CreateTableWhiteboxRuleSet {

    static final String QUESTION_TYPE = "CREATE_TABLE";
    private static final WhiteboxPenaltyUnit PCT = WhiteboxPenaltyUnit.PERCENTAGE_OF_QUESTION;
    private static final Pattern WITH_NOCHECK = Pattern.compile("(?i)\\bWITH\\s+NOCHECK\\b");
    private static final Pattern DROP_TABLE = Pattern.compile("(?i)\\bDROP\\s+TABLE\\b");
    private static final Pattern SELECT_INTO = Pattern.compile(
            "(?i)\\bSELECT\\b[\\s\\S]{0,500}?\\bINTO\\s+(?:\\[[^\\]]+\\]|[\\w$#@.]+)"
                    + "[\\s\\S]{0,500}?\\bFROM\\b");

    private CreateTableWhiteboxRuleSet() {
    }

    static void registerInto(Map<String, WhiteboxCatalogEntry> entries,
                             Map<String, WhiteboxRuleEvaluator> evaluators) {
        reg(entries, evaluators, "REQUIRED_CONSTRAINT_NAME", WhiteboxRuleType.REQUIRED, "CONSTRAINTS",
                "Constraint phải được đặt tên",
                "PRIMARY KEY, FOREIGN KEY, UNIQUE và CHECK phải có CONSTRAINT <name>.",
                10, WhiteboxFeature.CONSTRAINT_NAME, WhiteboxPolicy.REQUIRE, List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !facts(ctx).allRelevantConstraintsAreNamed(),
                        "Phát hiện PK/FK/UNIQUE/CHECK không có tiền tố CONSTRAINT <name>"));

        reg(entries, evaluators, "FORBIDDEN_IDENTITY", WhiteboxRuleType.FORBIDDEN, "COLUMNS",
                "Cấm IDENTITY",
                "Không được dùng IDENTITY; sinh viên phải tự quản lý giá trị khóa.",
                10, WhiteboxFeature.IDENTITY, WhiteboxPolicy.FORBID,
                List.of("REQUIRED_IDENTITY"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        facts(ctx).hasIdentity(), "Phát hiện cột IDENTITY"));

        reg(entries, evaluators, "FORBIDDEN_DEPRECATED_TYPE", WhiteboxRuleType.FORBIDDEN, "DATA_TYPE",
                "Cấm kiểu TEXT/NTEXT/IMAGE",
                "Không được dùng các kiểu dữ liệu SQL Server đã deprecated: TEXT, NTEXT, IMAGE.",
                5, WhiteboxFeature.DEPRECATED_DATA_TYPE, WhiteboxPolicy.FORBID, List.of(), List.of(),
                (ctx, rule) -> {
                    String type = facts(ctx).firstDeprecatedType();
                    return WhiteboxEvaluation.of(type != null,
                            type == null ? null : "Phát hiện kiểu dữ liệu deprecated: " + type);
                });

        reg(entries, evaluators, "FORBIDDEN_NOCHECK", WhiteboxRuleType.FORBIDDEN, "SAFETY",
                "Cấm WITH NOCHECK",
                "Không được dùng WITH NOCHECK khi thêm constraint.",
                20, WhiteboxFeature.NOCHECK, WhiteboxPolicy.FORBID, List.of(), List.of(),
                (ctx, rule) -> forbidden(ctx, WITH_NOCHECK, "Phát hiện WITH NOCHECK"));

        reg(entries, evaluators, "FORBIDDEN_DROP_TABLE", WhiteboxRuleType.FORBIDDEN, "SAFETY",
                "Cấm DROP TABLE",
                "Không được DROP TABLE trong bài CREATE TABLE.",
                10, WhiteboxFeature.DROP_TABLE, WhiteboxPolicy.FORBID, List.of(), List.of(),
                (ctx, rule) -> forbidden(ctx, DROP_TABLE, "Phát hiện DROP TABLE"));

        reg(entries, evaluators, "FORBIDDEN_SELECT_INTO", WhiteboxRuleType.FORBIDDEN, "SAFETY",
                "Cấm SELECT INTO",
                "Không được tạo bảng bằng SELECT ... INTO thay cho DDL CREATE TABLE.",
                30, WhiteboxFeature.SELECT_INTO, WhiteboxPolicy.FORBID, List.of(), List.of(),
                (ctx, rule) -> forbidden(ctx, SELECT_INTO, "Phát hiện SELECT ... INTO ... FROM"));
    }

    private static CreateTableDdlFacts facts(SelectWhiteboxContext context) {
        return CreateTableDdlFacts.analyze(context.rawSql());
    }

    private static WhiteboxEvaluation forbidden(
            SelectWhiteboxContext context, Pattern pattern, String message) {
        return WhiteboxEvaluation.of(facts(context).contains(pattern), message);
    }

    private static void reg(Map<String, WhiteboxCatalogEntry> entries,
                            Map<String, WhiteboxRuleEvaluator> evaluators,
                            String ruleId, WhiteboxRuleType type, String group, String label,
                            String description, double defaultPenaltyPct,
                            WhiteboxFeature feature, WhiteboxPolicy policy,
                            List<String> conflictsWith, List<WhiteboxParamSpec> params,
                            WhiteboxRuleEvaluator evaluator) {
        entries.put(ruleId, new WhiteboxCatalogEntry(
                ruleId, type, group, label, description, WhiteboxSeverity.WARNING_ONLY,
                BigDecimal.valueOf(defaultPenaltyPct), PCT, false,
                List.of(QUESTION_TYPE), params,
                feature.name(), feature.label(), WhiteboxFeatureKind.BOOLEAN,
                policy, policy.label(), conflictsWith));
        evaluators.put(ruleId, evaluator);
    }
}
