package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static graduation_project_be.application.usecases.grading.whitebox.RoutineWhiteboxPatterns.CURSOR_DECLARE;
import static graduation_project_be.application.usecases.grading.whitebox.RoutineWhiteboxPatterns.PRINT_STMT;
import static graduation_project_be.application.usecases.grading.whitebox.RoutineWhiteboxPatterns.ROLLBACK;
import static graduation_project_be.application.usecases.grading.whitebox.RoutineWhiteboxPatterns.SET_NOCOUNT_ON;
import static graduation_project_be.application.usecases.grading.whitebox.RoutineWhiteboxPatterns.find;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.AFTER_OR_FOR_EVENT;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.hasTriggerName;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.DELETED_TABLE;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.INSERTED_TABLE;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.INSTEAD_OF;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.UPDATE_FN_CHECK;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.missingEvents;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.multiRowUnsafe;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.multiRowUnsafeEvidence;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.onTable;
import static graduation_project_be.application.usecases.grading.whitebox.TriggerWhiteboxPatterns.returnsResultSet;

/**
 * White-box rule catalog for TRIGGER question type (14 rules). All rules use {@code parserRequired=false}
 * — text-scan only, no JSQLParser dependency. Rule ids are namespaced with {@code TR_} to stay unique in
 * the shared catalog map (cf. {@code SP_} for stored procedures).
 */
final class TriggerWhiteboxRuleSet {

    static final String QUESTION_TYPE = "TRIGGER";
    private static final WhiteboxPenaltyUnit PCT = WhiteboxPenaltyUnit.PERCENTAGE_OF_QUESTION;

    private TriggerWhiteboxRuleSet() {
    }

    static void registerInto(Map<String, WhiteboxCatalogEntry> entries,
                             Map<String, WhiteboxRuleEvaluator> evaluators) {

        // ---- Group: Pseudo-tables ----
        reg(entries, evaluators, "TR_REQUIRED_INSERTED_TABLE", WhiteboxRuleType.REQUIRED, "PSEUDO_TABLE",
                "Bắt buộc dùng INSERTED", "Trigger phải dùng pseudo-table INSERTED thay vì đọc lại bảng gốc.",
                20, false,
                WhiteboxFeature.INSERTED_TABLE, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), INSERTED_TABLE),
                        "Không tìm thấy tham chiếu bảng INSERTED"));

        reg(entries, evaluators, "TR_REQUIRED_DELETED_TABLE", WhiteboxRuleType.REQUIRED, "PSEUDO_TABLE",
                "Bắt buộc dùng DELETED", "Trigger UPDATE/DELETE phải dùng pseudo-table DELETED để lấy giá trị cũ.",
                20, false,
                WhiteboxFeature.DELETED_TABLE, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), DELETED_TABLE),
                        "Không tìm thấy tham chiếu bảng DELETED"));

        // ---- Group: Multi-row safety ----
        reg(entries, evaluators, "TR_REQUIRED_MULTIROW_SAFE", WhiteboxRuleType.REQUIRED, "MULTIROW",
                "Bắt buộc an toàn nhiều dòng", "Cấm gán scalar (SELECT @x = ... FROM INSERTED/DELETED) kiểu xử lý 1 hàng "
                        + "— sẽ sai khi DML tác động nhiều dòng. Phải xử lý set-based (JOIN INSERTED/DELETED).",
                25, false,
                WhiteboxFeature.MULTIROW_SAFETY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> {
                    boolean unsafe = multiRowUnsafe(ctx.cleanedSql());
                    String evidence = unsafe
                            ? "Gán scalar từ INSERTED/DELETED (không an toàn nhiều dòng): "
                                    + multiRowUnsafeEvidence(ctx.cleanedSql())
                            : null;
                    return WhiteboxEvaluation.of(unsafe, evidence);
                });

        // ---- Group: Timing ----
        reg(entries, evaluators, "TR_REQUIRED_AFTER_TRIGGER", WhiteboxRuleType.REQUIRED, "TIMING",
                "Bắt buộc là AFTER trigger", "Trigger phải khai báo AFTER/FOR <event> và không được dùng INSTEAD OF.",
                15, false,
                WhiteboxFeature.TRIGGER_TIMING, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of("TR_REQUIRED_INSTEAD_OF_TRIGGER"), List.of(),
                (ctx, rule) -> {
                    String sql = ctx.cleanedSql();
                    boolean hasAfter = find(sql, AFTER_OR_FOR_EVENT);
                    boolean hasInsteadOf = find(sql, INSTEAD_OF);
                    boolean violated = !hasAfter || hasInsteadOf;
                    String actual = hasInsteadOf ? "Phát hiện INSTEAD OF (đề yêu cầu AFTER)"
                            : "Không tìm thấy khai báo AFTER/FOR <event>";
                    return WhiteboxEvaluation.of(violated, actual);
                });

        reg(entries, evaluators, "TR_REQUIRED_INSTEAD_OF_TRIGGER", WhiteboxRuleType.REQUIRED, "TIMING",
                "Bắt buộc là INSTEAD OF trigger", "Trigger phải khai báo INSTEAD OF (đề yêu cầu chặn thao tác gốc).",
                15, false,
                WhiteboxFeature.TRIGGER_TIMING, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of("TR_REQUIRED_AFTER_TRIGGER"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), INSTEAD_OF),
                        "Không tìm thấy khai báo INSTEAD OF"));

        // ---- Group: Target table ----
        reg(entries, evaluators, "TR_REQUIRED_ON_SPECIFIC_TABLE", WhiteboxRuleType.REQUIRED, "TABLE",
                "Bắt buộc gắn đúng bảng", "Trigger phải được gắn (ON) đúng bảng cấu hình trong params.table_name.",
                20, false,
                WhiteboxFeature.TRIGGER_TABLE, WhiteboxFeatureKind.COMPOSITE, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(WhiteboxParamSpec.string("table_name", "Tên bảng gắn trigger", true)),
                (ctx, rule) -> {
                    String table = WhiteboxParams.stringParam(rule, "table_name", "");
                    if (table.isBlank()) {
                        return WhiteboxEvaluation.of(false, "Không cấu hình table_name");
                    }
                    boolean violated = !onTable(ctx.cleanedSql(), table);
                    return WhiteboxEvaluation.of(violated,
                            violated ? "Không tìm thấy ON " + table : "Gắn đúng bảng " + table);
                });

        // ---- Group: Name ----
        reg(entries, evaluators, "TR_REQUIRED_NAME_MATCH", WhiteboxRuleType.REQUIRED, "NAME",
                "Bắt buộc đúng tên trigger", "Trigger phải được khai báo (CREATE TRIGGER) đúng tên cấu hình "
                        + "trong params.trigger_name.",
                10, false,
                WhiteboxFeature.TRIGGER_NAME, WhiteboxFeatureKind.COMPOSITE, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(WhiteboxParamSpec.string("trigger_name", "Tên trigger mong đợi", true)),
                (ctx, rule) -> {
                    String name = WhiteboxParams.stringParam(rule, "trigger_name", "");
                    if (name.isBlank()) {
                        return WhiteboxEvaluation.of(false, "Không cấu hình trigger_name");
                    }
                    boolean violated = !hasTriggerName(ctx.cleanedSql(), name);
                    return WhiteboxEvaluation.of(violated,
                            violated ? "Tên trigger không khớp: mong đợi " + name : "Đúng tên " + name);
                });

        // ---- Group: Events ----
        reg(entries, evaluators, "TR_REQUIRED_FOR_EVENT", WhiteboxRuleType.REQUIRED, "EVENT",
                "Bắt buộc đúng sự kiện", "Trigger phải khai báo đầy đủ các sự kiện trong params.events (vd [\"INSERT\",\"UPDATE\"]).",
                15, false,
                WhiteboxFeature.TRIGGER_EVENT, WhiteboxFeatureKind.SET, WhiteboxPolicy.REQUIRE_ALL,
                List.of(), List.of(WhiteboxParamSpec.stringList("events", "Sự kiện bắt buộc (INSERT/UPDATE/DELETE)", true)),
                (ctx, rule) -> {
                    List<String> required = WhiteboxParams.stringList(rule, "events");
                    if (required.isEmpty()) {
                        return WhiteboxEvaluation.of(false, "Không cấu hình events");
                    }
                    List<String> missing = missingEvents(ctx.cleanedSql(), required);
                    return WhiteboxEvaluation.of(!missing.isEmpty(),
                            "Thiếu sự kiện: " + String.join(", ", missing));
                });

        // ---- Group: Column-change check ----
        reg(entries, evaluators, "TR_REQUIRED_UPDATE_FN_CHECK", WhiteboxRuleType.REQUIRED, "COLUMN_CHECK",
                "Bắt buộc kiểm tra cột thay đổi", "Trigger UPDATE nên dùng UPDATE(col) hoặc COLUMNS_UPDATED() để chỉ chạy "
                        + "logic khi cột liên quan thay đổi.",
                10, false,
                WhiteboxFeature.UPDATE_COLUMN_CHECK, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), UPDATE_FN_CHECK),
                        "Không tìm thấy UPDATE(col) hoặc COLUMNS_UPDATED()"));

        // ---- Group: Settings ----
        reg(entries, evaluators, "TR_REQUIRED_SET_NOCOUNT_ON", WhiteboxRuleType.REQUIRED, "SETTINGS",
                "Bắt buộc SET NOCOUNT ON", "Trigger nên khai báo SET NOCOUNT ON ở đầu để không phá rows-affected của ứng dụng.",
                5, false,
                WhiteboxFeature.SET_NOCOUNT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        !find(ctx.cleanedSql(), SET_NOCOUNT_ON),
                        "Không tìm thấy SET NOCOUNT ON"));

        // ---- Group: Forbidden ----
        reg(entries, evaluators, "TR_FORBIDDEN_ROLLBACK_IN_TRIGGER", WhiteboxRuleType.FORBIDDEN, "TRANSACTION",
                "Cấm ROLLBACK trong trigger", "Cấm ROLLBACK trong trigger — sẽ hủy cả transaction bên ngoài; nên dùng THROW.",
                15, false,
                WhiteboxFeature.ROLLBACK_IN_TRIGGER, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), ROLLBACK),
                        "Phát hiện ROLLBACK trong trigger"));

        reg(entries, evaluators, "TR_FORBIDDEN_RESULTSET_IN_TRIGGER", WhiteboxRuleType.FORBIDDEN, "SIDE_EFFECT",
                "Cấm trả result set về client", "Cấm SELECT trả result set về client (SELECT không gán biến và không INTO) "
                        + "— gây lỗi ứng dụng gọi DML.",
                10, false,
                WhiteboxFeature.RESULTSET_IN_TRIGGER, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        returnsResultSet(ctx.cleanedSql()),
                        "Phát hiện SELECT trả result set về client"));

        reg(entries, evaluators, "TR_FORBIDDEN_CURSOR", WhiteboxRuleType.FORBIDDEN, "CURSOR",
                "Cấm CURSOR", "Cấm dùng CURSOR trong trigger — phải xử lý set-based.",
                20, false,
                WhiteboxFeature.CURSOR, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), CURSOR_DECLARE),
                        "Phát hiện DECLARE CURSOR"));

        reg(entries, evaluators, "TR_FORBIDDEN_PRINT", WhiteboxRuleType.FORBIDDEN, "DEBUG",
                "Cấm PRINT", "Cấm dùng PRINT debug trong trigger.",
                5, false,
                WhiteboxFeature.PRINT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(
                        find(ctx.cleanedSql(), PRINT_STMT),
                        "Phát hiện câu lệnh PRINT"));
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
