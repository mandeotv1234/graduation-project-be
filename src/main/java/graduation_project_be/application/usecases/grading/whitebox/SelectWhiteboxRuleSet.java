package graduation_project_be.application.usecases.grading.whitebox;

import graduation_project_be.application.usecases.grading.QueryStructureFacts;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static graduation_project_be.application.usecases.grading.whitebox.SelectWhiteboxPatterns.*;

/**
 * The catalog of SELECT_QUERY white-box rules (the 7 vision groups) with a real evaluator for each.
 * Every rule registered here is evaluator-backed and unit-tested; the catalog API exposes exactly
 * this set. Detection is text-safe regex on the cleaned SQL except the parser-dependent subquery /
 * old-join rules, which read JSQLParser structural facts and become UNVERIFIED when parsing fails.
 *
 * <p>Each entry also carries additive feature-policy authoring metadata (feature, featureKind,
 * policy, conflictsWith) so the frontend can offer a feature-first add-rule flow. The metadata never
 * affects grading — the engine still dispatches by {@code rule_id}.
 */
final class SelectWhiteboxRuleSet {

    static final String QUESTION_TYPE = "SELECT_QUERY";
    // FUNCTION / STORED_PROCEDURE / TRIGGER bodies may contain SELECT statements, so all
    // SELECT rules are surfaced in their catalogs too. Parser-dependent rules (parserRequired=true)
    // gracefully degrade to UNVERIFIED (pass, no deduction) when the routine body cannot be parsed.
    private static final List<String> APPLICABLE_TYPES =
            List.of(QUESTION_TYPE, "FUNCTION", "STORED_PROCEDURE");
    private static final WhiteboxPenaltyUnit PCT = WhiteboxPenaltyUnit.PERCENTAGE_OF_QUESTION;

    private SelectWhiteboxRuleSet() {
    }

    static void registerInto(Map<String, WhiteboxCatalogEntry> entries,
                             Map<String, WhiteboxRuleEvaluator> evaluators) {
        // ---- Group 1: Subquery & CTE ----
        reg(entries, evaluators, "FORBIDDEN_SUBQUERY", WhiteboxRuleType.FORBIDDEN, "SUBQUERY_CTE",
                "Cấm tất cả truy vấn lồng", "Cấm mọi SELECT lồng trong bất kỳ mệnh đề nào (SELECT/FROM/WHERE/HAVING).",
                25, true,
                WhiteboxFeature.SUBQUERY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("MAX_SUBQUERY_DEPTH",
                        "FORBIDDEN_SUBQUERY_IN_SELECT", "FORBIDDEN_SUBQUERY_IN_FROM",
                        "FORBIDDEN_SUBQUERY_IN_WHERE", "FORBIDDEN_SUBQUERY_IN_HAVING"),
                List.of(),
                (ctx, rule) -> {
                    QueryStructureFacts f = ctx.facts();
                    boolean v = f.hasSubqueryInSelect() || f.hasSubqueryInFrom()
                            || f.hasSubqueryInWhere() || f.hasSubqueryInHaving();
                    return WhiteboxEvaluation.of(v, "Phát hiện truy vấn con lồng");
                });
        reg(entries, evaluators, "FORBIDDEN_SUBQUERY_IN_SELECT", WhiteboxRuleType.FORBIDDEN, "SUBQUERY_CTE",
                "Cấm subquery trong SELECT", "Cấm dùng SELECT lồng trong danh sách cột SELECT.",
                15, true,
                WhiteboxFeature.SUBQUERY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("FORBIDDEN_SUBQUERY"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(ctx.facts().hasSubqueryInSelect(),
                        "Phát hiện truy vấn con trong SELECT list"));
        reg(entries, evaluators, "FORBIDDEN_SUBQUERY_IN_FROM", WhiteboxRuleType.FORBIDDEN, "SUBQUERY_CTE",
                "Cấm subquery trong FROM", "Cấm dùng bảng dẫn xuất (derived table) trong FROM.",
                15, true,
                WhiteboxFeature.SUBQUERY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("FORBIDDEN_SUBQUERY"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(ctx.facts().hasSubqueryInFrom(),
                        "Phát hiện derived table (subquery) trong FROM"));
        reg(entries, evaluators, "FORBIDDEN_SUBQUERY_IN_WHERE", WhiteboxRuleType.FORBIDDEN, "SUBQUERY_CTE",
                "Cấm subquery trong WHERE", "Cấm dùng SELECT lồng trong điều kiện WHERE.",
                20, true,
                WhiteboxFeature.SUBQUERY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("FORBIDDEN_SUBQUERY"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(ctx.facts().hasSubqueryInWhere(),
                        "Phát hiện truy vấn con trong WHERE"));
        reg(entries, evaluators, "FORBIDDEN_SUBQUERY_IN_HAVING", WhiteboxRuleType.FORBIDDEN, "SUBQUERY_CTE",
                "Cấm subquery trong HAVING", "Cấm dùng SELECT lồng trong điều kiện HAVING.",
                10, true,
                WhiteboxFeature.SUBQUERY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("FORBIDDEN_SUBQUERY"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(ctx.facts().hasSubqueryInHaving(),
                        "Phát hiện truy vấn con trong HAVING"));
        reg(entries, evaluators, "MAX_SUBQUERY_DEPTH", WhiteboxRuleType.LIMIT, "SUBQUERY_CTE",
                "Giới hạn độ sâu subquery", "Độ sâu lồng truy vấn con không vượt quá max_depth.",
                15, true,
                WhiteboxFeature.SUBQUERY, WhiteboxFeatureKind.NUMERIC, WhiteboxPolicy.AT_MOST,
                List.of("FORBIDDEN_SUBQUERY"),
                List.of(WhiteboxParamSpec.number("max_depth", "Độ sâu tối đa", true, 1)),
                (ctx, rule) -> {
                    int max = WhiteboxParams.intParam(rule, "max_depth", 1);
                    int depth = ctx.facts().maxNestingDepth();
                    return WhiteboxEvaluation.of(depth > max, "Độ sâu lồng = " + depth + " > " + max);
                });
        reg(entries, evaluators, "FORBIDDEN_CORRELATED_SUBQUERY", WhiteboxRuleType.FORBIDDEN, "SUBQUERY_CTE",
                "Cấm subquery tương quan", "Cấm truy vấn con tham chiếu alias của truy vấn ngoài.",
                20, true,
                WhiteboxFeature.CORRELATED_SUBQUERY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(ctx.facts().hasCorrelatedSubquery(),
                        "Có truy vấn con tương quan (tham chiếu bảng ngoài)"));
        reg(entries, evaluators, "FORBIDDEN_CTE", WhiteboxRuleType.FORBIDDEN, "SUBQUERY_CTE",
                "Cấm CTE", "Cấm dùng CTE (WITH ... AS (...)).",
                15, false,
                WhiteboxFeature.CTE, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("REQUIRED_CTE"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(find(ctx.cleanedSql(), CTE), "Phát hiện CTE (WITH ... AS)"));
        reg(entries, evaluators, "REQUIRED_CTE", WhiteboxRuleType.REQUIRED, "SUBQUERY_CTE",
                "Bắt buộc dùng CTE", "Bắt buộc dùng CTE (WITH ... AS (...)).",
                20, false,
                WhiteboxFeature.CTE, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of("FORBIDDEN_CTE"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(!find(ctx.cleanedSql(), CTE), "Không tìm thấy CTE"));

        // ---- Group 2: JOIN ----
        reg(entries, evaluators, "REQUIRED_JOIN", WhiteboxRuleType.REQUIRED, "JOIN",
                "Bắt buộc dùng JOIN", "Bắt buộc dùng từ khóa JOIN.",
                20, false,
                WhiteboxFeature.JOIN, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(!find(ctx.cleanedSql(), JOIN), "Không tìm thấy từ khóa JOIN"));
        reg(entries, evaluators, "FORBIDDEN_OLD_JOIN_SYNTAX", WhiteboxRuleType.FORBIDDEN, "JOIN",
                "Cấm comma-join kiểu cũ", "Cấm nối bảng bằng dấu phẩy (FROM A, B WHERE ...).",
                15, true,
                WhiteboxFeature.OLD_JOIN_SYNTAX, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> {
                    QueryStructureFacts f = ctx.facts();
                    boolean v = (f.fromTableCount() - 1) > f.joinCount();
                    return WhiteboxEvaluation.of(v, "Dùng comma-join kiểu cũ (FROM A, B)");
                });
        reg(entries, evaluators, "REQUIRED_LEFT_JOIN", WhiteboxRuleType.REQUIRED, "JOIN",
                "Bắt buộc LEFT JOIN", "Bắt buộc dùng LEFT (OUTER) JOIN.",
                20, false,
                WhiteboxFeature.LEFT_JOIN, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(!find(ctx.cleanedSql(), LEFT_JOIN), "Không tìm thấy LEFT JOIN"));
        reg(entries, evaluators, "REQUIRED_INNER_JOIN", WhiteboxRuleType.REQUIRED, "JOIN",
                "Bắt buộc INNER JOIN tường minh", "Bắt buộc viết tường minh INNER JOIN.",
                5, false,
                WhiteboxFeature.INNER_JOIN, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(!find(ctx.cleanedSql(), INNER_JOIN), "Không tìm thấy INNER JOIN"));
        reg(entries, evaluators, "FORBIDDEN_CROSS_JOIN", WhiteboxRuleType.FORBIDDEN, "JOIN",
                "Cấm CROSS JOIN", "Cấm dùng CROSS JOIN.",
                15, false,
                WhiteboxFeature.CROSS_JOIN, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(find(ctx.cleanedSql(), CROSS_JOIN), "Phát hiện CROSS JOIN"));
        reg(entries, evaluators, "MAX_JOIN_COUNT", WhiteboxRuleType.LIMIT, "JOIN",
                "Giới hạn số JOIN", "Số lượng JOIN không vượt quá max_joins.",
                10, false,
                WhiteboxFeature.JOIN, WhiteboxFeatureKind.NUMERIC, WhiteboxPolicy.AT_MOST,
                List.of(),
                List.of(WhiteboxParamSpec.number("max_joins", "Số JOIN tối đa", true, 2)),
                (ctx, rule) -> {
                    int n = count(ctx.cleanedSql(), JOIN);
                    int max = WhiteboxParams.intParam(rule, "max_joins", 2);
                    return WhiteboxEvaluation.of(n > max, "Số JOIN = " + n + " > " + max);
                });

        // ---- Group 3: SELECT list & DISTINCT ----
        reg(entries, evaluators, "FORBIDDEN_SELECT_STAR", WhiteboxRuleType.FORBIDDEN, "SELECT_LIST",
                "Cấm SELECT *", "Cấm dùng SELECT * hoặc alias.*.",
                10, false,
                WhiteboxFeature.SELECT_STAR, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(find(ctx.cleanedSql(), SELECT_STAR), "Phát hiện SELECT *"));
        reg(entries, evaluators, "FORBIDDEN_DISTINCT", WhiteboxRuleType.FORBIDDEN, "SELECT_LIST",
                "Cấm DISTINCT", "Cấm dùng DISTINCT che lỗi JOIN nhân bản hàng.",
                10, false,
                WhiteboxFeature.DISTINCT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("REQUIRED_DISTINCT"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(find(ctx.cleanedSql(), DISTINCT), "Phát hiện SELECT DISTINCT"));
        reg(entries, evaluators, "REQUIRED_DISTINCT", WhiteboxRuleType.REQUIRED, "SELECT_LIST",
                "Bắt buộc DISTINCT", "Bắt buộc dùng DISTINCT.",
                10, false,
                WhiteboxFeature.DISTINCT, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of("FORBIDDEN_DISTINCT"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(!find(ctx.cleanedSql(), DISTINCT), "Không tìm thấy DISTINCT"));

        // ---- Group 4: Aggregate · GROUP BY · HAVING ----
        reg(entries, evaluators, "REQUIRED_GROUP_BY", WhiteboxRuleType.REQUIRED, "AGGREGATE",
                "Bắt buộc GROUP BY", "Bắt buộc có GROUP BY.",
                20, false,
                WhiteboxFeature.GROUP_BY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of("FORBIDDEN_GROUP_BY"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(!find(ctx.cleanedSql(), GROUP_BY), "Không tìm thấy GROUP BY"));
        reg(entries, evaluators, "FORBIDDEN_GROUP_BY", WhiteboxRuleType.FORBIDDEN, "AGGREGATE",
                "Cấm GROUP BY", "Cấm dùng GROUP BY (yêu cầu cách khác, vd window function).",
                15, false,
                WhiteboxFeature.GROUP_BY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("REQUIRED_GROUP_BY"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(find(ctx.cleanedSql(), GROUP_BY), "Phát hiện GROUP BY"));
        reg(entries, evaluators, "REQUIRED_HAVING", WhiteboxRuleType.REQUIRED, "AGGREGATE",
                "Bắt buộc HAVING", "Bắt buộc có HAVING (lọc sau gom nhóm).",
                15, false,
                WhiteboxFeature.HAVING, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of("FORBIDDEN_HAVING"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(!find(ctx.cleanedSql(), HAVING), "Không tìm thấy HAVING"));
        reg(entries, evaluators, "FORBIDDEN_HAVING", WhiteboxRuleType.FORBIDDEN, "AGGREGATE",
                "Cấm HAVING", "Cấm dùng HAVING (điều kiện hàng phải ở WHERE).",
                10, false,
                WhiteboxFeature.HAVING, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("REQUIRED_HAVING"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(find(ctx.cleanedSql(), HAVING), "Phát hiện HAVING"));
        reg(entries, evaluators, "REQUIRED_AGGREGATE_FUNCTION", WhiteboxRuleType.REQUIRED, "AGGREGATE",
                "Bắt buộc hàm tổng hợp", "Bắt buộc dùng ít nhất một hàm trong functions (mặc định SUM/COUNT/AVG/MIN/MAX).",
                20, false,
                WhiteboxFeature.AGGREGATE_FUNCTION, WhiteboxFeatureKind.SET, WhiteboxPolicy.REQUIRE_ANY,
                List.of("FORBIDDEN_AGGREGATE_FUNCTION"),
                List.of(WhiteboxParamSpec.stringList("functions", "Danh sách hàm", false)),
                (ctx, rule) -> {
                    List<String> fns = functionsOrDefault(rule, DEFAULT_AGGREGATES);
                    boolean any = anyFunctionPresent(ctx.cleanedSql(), fns);
                    return WhiteboxEvaluation.of(!any, "Không dùng hàm tổng hợp: " + fns);
                });
        reg(entries, evaluators, "FORBIDDEN_AGGREGATE_FUNCTION", WhiteboxRuleType.FORBIDDEN, "AGGREGATE",
                "Cấm hàm tổng hợp cụ thể", "Cấm dùng các hàm trong functions (mặc định SUM/COUNT/AVG/MIN/MAX).",
                10, false,
                WhiteboxFeature.AGGREGATE_FUNCTION, WhiteboxFeatureKind.SET, WhiteboxPolicy.FORBID_ANY,
                List.of("REQUIRED_AGGREGATE_FUNCTION"),
                List.of(WhiteboxParamSpec.stringList("functions", "Danh sách hàm", false)),
                (ctx, rule) -> {
                    List<String> fns = functionsOrDefault(rule, DEFAULT_AGGREGATES);
                    String hit = firstFunctionPresent(ctx.cleanedSql(), fns);
                    return WhiteboxEvaluation.of(hit != null, "Dùng hàm bị cấm: " + hit);
                });

        // ---- Group 5: ORDER BY & Window ----
        reg(entries, evaluators, "REQUIRED_ORDER_BY", WhiteboxRuleType.REQUIRED, "ORDER_WINDOW",
                "Bắt buộc ORDER BY", "Bắt buộc có ORDER BY.",
                10, false,
                WhiteboxFeature.ORDER_BY, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of(), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(!find(ctx.cleanedSql(), ORDER_BY), "Không tìm thấy ORDER BY"));
        reg(entries, evaluators, "FORBIDDEN_WINDOW_FUNCTION", WhiteboxRuleType.FORBIDDEN, "ORDER_WINDOW",
                "Cấm window function", "Cấm dùng window function (OVER(...)).",
                20, false,
                WhiteboxFeature.WINDOW_FUNCTION, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("REQUIRED_WINDOW_FUNCTION"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(find(ctx.cleanedSql(), WINDOW), "Phát hiện OVER(...)"));
        reg(entries, evaluators, "REQUIRED_WINDOW_FUNCTION", WhiteboxRuleType.REQUIRED, "ORDER_WINDOW",
                "Bắt buộc window function", "Bắt buộc dùng window function (OVER(...)).",
                25, false,
                WhiteboxFeature.WINDOW_FUNCTION, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.REQUIRE,
                List.of("FORBIDDEN_WINDOW_FUNCTION"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(!find(ctx.cleanedSql(), WINDOW), "Không tìm thấy OVER(...)"));

        // ---- Group 6: Set operations ----
        reg(entries, evaluators, "FORBIDDEN_SET_OPERATOR", WhiteboxRuleType.FORBIDDEN, "SET_OPERATION",
                "Cấm toán tử tập hợp", "Cấm UNION / INTERSECT / EXCEPT.",
                15, false,
                WhiteboxFeature.SET_OPERATOR, WhiteboxFeatureKind.BOOLEAN, WhiteboxPolicy.FORBID,
                List.of("REQUIRED_SET_OPERATOR"), List.of(),
                (ctx, rule) -> WhiteboxEvaluation.of(find(ctx.cleanedSql(), SET_OPERATOR),
                        "Phát hiện UNION/INTERSECT/EXCEPT"));
        reg(entries, evaluators, "REQUIRED_SET_OPERATOR", WhiteboxRuleType.REQUIRED, "SET_OPERATION",
                "Bắt buộc toán tử tập hợp", "Bắt buộc dùng toán tử trong operators (mặc định UNION/INTERSECT/EXCEPT).",
                20, false,
                WhiteboxFeature.SET_OPERATOR, WhiteboxFeatureKind.SET, WhiteboxPolicy.REQUIRE_ANY,
                List.of("FORBIDDEN_SET_OPERATOR"),
                List.of(WhiteboxParamSpec.stringList("operators", "Danh sách toán tử", false)),
                (ctx, rule) -> {
                    List<String> ops = listOrDefault(WhiteboxParams.stringList(rule, "operators"), DEFAULT_SET_OPERATORS);
                    boolean any = anyKeywordPresent(ctx.cleanedSql(), ops);
                    return WhiteboxEvaluation.of(!any, "Không dùng toán tử tập hợp: " + ops);
                });

        // ---- Group 7: Generic function / keyword ----
        reg(entries, evaluators, "FORBIDDEN_FUNCTION", WhiteboxRuleType.FORBIDDEN, "GENERIC",
                "Cấm hàm cụ thể", "Cấm gọi các hàm liệt kê trong functions.",
                10, false,
                WhiteboxFeature.FUNCTION, WhiteboxFeatureKind.SET, WhiteboxPolicy.FORBID_ANY,
                List.of(),
                List.of(WhiteboxParamSpec.stringList("functions", "Danh sách hàm", true)),
                (ctx, rule) -> {
                    List<String> fns = WhiteboxParams.stringList(rule, "functions");
                    if (fns.isEmpty()) {
                        return WhiteboxEvaluation.pass();
                    }
                    String hit = firstFunctionPresent(ctx.cleanedSql(), fns);
                    return WhiteboxEvaluation.of(hit != null, "Dùng hàm bị cấm: " + hit);
                });
        reg(entries, evaluators, "REQUIRED_FUNCTION", WhiteboxRuleType.REQUIRED, "GENERIC",
                "Bắt buộc hàm cụ thể", "Bắt buộc gọi tất cả các hàm liệt kê trong functions.",
                10, false,
                WhiteboxFeature.FUNCTION, WhiteboxFeatureKind.SET, WhiteboxPolicy.REQUIRE_ALL,
                List.of(),
                List.of(WhiteboxParamSpec.stringList("functions", "Danh sách hàm", true)),
                (ctx, rule) -> {
                    List<String> fns = WhiteboxParams.stringList(rule, "functions");
                    if (fns.isEmpty()) {
                        return WhiteboxEvaluation.pass();
                    }
                    List<String> missing = missingFunctions(ctx.cleanedSql(), fns);
                    return WhiteboxEvaluation.of(!missing.isEmpty(), "Thiếu hàm: " + missing);
                });
        reg(entries, evaluators, "FORBIDDEN_KEYWORD", WhiteboxRuleType.FORBIDDEN, "GENERIC",
                "Cấm từ khóa cụ thể", "Cấm dùng các từ khóa liệt kê trong keywords.",
                10, false,
                WhiteboxFeature.KEYWORD, WhiteboxFeatureKind.SET, WhiteboxPolicy.FORBID_ANY,
                List.of(),
                List.of(WhiteboxParamSpec.stringList("keywords", "Danh sách từ khóa", true)),
                (ctx, rule) -> {
                    List<String> kws = WhiteboxParams.stringList(rule, "keywords");
                    if (kws.isEmpty()) {
                        return WhiteboxEvaluation.pass();
                    }
                    String hit = firstKeywordPresent(ctx.cleanedSql(), kws);
                    return WhiteboxEvaluation.of(hit != null, "Dùng từ khóa bị cấm: " + hit);
                });
        reg(entries, evaluators, "REQUIRED_KEYWORD", WhiteboxRuleType.REQUIRED, "GENERIC",
                "Bắt buộc từ khóa cụ thể", "Bắt buộc dùng tất cả các từ khóa liệt kê trong keywords.",
                10, false,
                WhiteboxFeature.KEYWORD, WhiteboxFeatureKind.SET, WhiteboxPolicy.REQUIRE_ALL,
                List.of(),
                List.of(WhiteboxParamSpec.stringList("keywords", "Danh sách từ khóa", true)),
                (ctx, rule) -> {
                    List<String> kws = WhiteboxParams.stringList(rule, "keywords");
                    if (kws.isEmpty()) {
                        return WhiteboxEvaluation.pass();
                    }
                    List<String> missing = missingKeywords(ctx.cleanedSql(), kws);
                    return WhiteboxEvaluation.of(!missing.isEmpty(), "Thiếu từ khóa: " + missing);
                });
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
                APPLICABLE_TYPES, params,
                feature.name(), feature.label(), featureKind, policy, policy.label(), conflictsWith));
        evaluators.put(ruleId, evaluator);
    }

    private static List<String> functionsOrDefault(WhiteboxRule rule, List<String> fallback) {
        return listOrDefault(WhiteboxParams.stringList(rule, "functions"), fallback);
    }

    private static List<String> listOrDefault(List<String> list, List<String> fallback) {
        return list.isEmpty() ? fallback : list;
    }

    private static boolean anyFunctionPresent(String sql, List<String> fns) {
        return firstFunctionPresent(sql, fns) != null;
    }

    private static String firstFunctionPresent(String sql, List<String> fns) {
        for (String fn : fns) {
            if (functionPresent(sql, fn)) {
                return fn;
            }
        }
        return null;
    }

    private static List<String> missingFunctions(String sql, List<String> fns) {
        return fns.stream().filter(fn -> !functionPresent(sql, fn)).toList();
    }

    private static boolean anyKeywordPresent(String sql, List<String> kws) {
        return firstKeywordPresent(sql, kws) != null;
    }

    private static String firstKeywordPresent(String sql, List<String> kws) {
        for (String kw : kws) {
            if (keywordPresent(sql, kw)) {
                return kw;
            }
        }
        return null;
    }

    private static List<String> missingKeywords(String sql, List<String> kws) {
        return kws.stream().filter(kw -> !keywordPresent(sql, kw)).toList();
    }
}
