package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.application.port.services.SelectQueryStructureAnalyzer;
import graduation_project_be.application.usecases.GradingTraceCollector;
import graduation_project_be.application.usecases.grading.QueryStructureFacts;
import graduation_project_be.domain.models.GradingTraceItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared white-box engine: applies {@code whitebox_rules} with {@code whitebox_settings} to a SQL
 * answer using catalog evaluators selected by {@code questionType + rule_id}, computes the capped
 * deduction, and emits white-box trace items. No white-box rules => {@link WhiteboxResult#empty()}
 * (the grader's black-box behaviour is unchanged). v1 only has SELECT_QUERY evaluators.
 */
public class WhiteboxEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String QUESTION_TYPE_SELECT = "SELECT_QUERY";
    private static final String TRACE_RULE_TARGET = "SQL_SCRIPT";
    private static final String TRACE_CONFIG_SUMMARY = "Whitebox SELECT";

    private final SelectQueryStructureAnalyzer selectAnalyzer;
    private final WhiteboxCatalog catalog;

    public WhiteboxEngine(SelectQueryStructureAnalyzer selectAnalyzer, WhiteboxCatalog catalog) {
        this.selectAnalyzer = selectAnalyzer;
        this.catalog = catalog;
    }

    /** Reads rules/settings from a {@code grading_payload} node, then evaluates. */
    public WhiteboxResult evaluateFromPayload(String questionType, String studentSql,
                                              JsonNode gradingPayload, BigDecimal questionPoints,
                                              boolean emitTrace) {
        List<WhiteboxRule> rules = WhiteboxRubricParser.rulesFromPayload(gradingPayload);
        WhiteboxSettings settings = WhiteboxRubricParser.settingsFromPayload(gradingPayload);
        return evaluate(questionType, studentSql, rules, settings, questionPoints, emitTrace);
    }

    public WhiteboxResult evaluate(String questionType, String studentSql, List<WhiteboxRule> rules,
                                   WhiteboxSettings settings, BigDecimal questionPoints, boolean emitTrace) {
        if (rules == null || rules.isEmpty()) {
            return WhiteboxResult.empty();
        }
        WhiteboxSettings effectiveSettings = settings == null ? WhiteboxSettings.defaults() : settings;
        BigDecimal points = questionPoints == null ? BigDecimal.ZERO : questionPoints;

        QueryStructureFacts facts = QUESTION_TYPE_SELECT.equals(questionType)
                ? selectAnalyzer.analyze(studentSql)
                : QueryStructureFacts.parseFailed();
        SelectWhiteboxContext context = new SelectWhiteboxContext(
                SqlTextPreprocessor.clean(studentSql), studentSql, facts);

        List<WhiteboxViolation> violations = new ArrayList<>();
        BigDecimal rawDeduction = BigDecimal.ZERO;
        int passCount = 0;
        int failCount = 0;
        int warnCount = 0;
        int unverifiedCount = 0;

        for (WhiteboxRule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            WhiteboxCatalogEntry entry = catalog.entry(rule.ruleId());
            WhiteboxRuleEvaluator evaluator = catalog.evaluator(rule.ruleId());
            if (entry == null || evaluator == null || !entry.questionTypes().contains(questionType)) {
                continue; // unknown / inapplicable rule id: skip rather than mis-grade
            }

            String label = (rule.description() != null && !rule.description().isBlank())
                    ? rule.description() : entry.label();
            BigDecimal configured = rule.penaltyValueOrZero();
            WhiteboxViolation violation;

            if (entry.parserRequired() && !facts.parseOk()) {
                unverifiedCount++;
                violation = new WhiteboxViolation(rule.ruleId(), WhiteboxStatus.UNVERIFIED, label,
                        entry.description(), null,
                        "Không phân tích được câu truy vấn (lỗi cú pháp); rule phụ thuộc parser được bỏ qua.",
                        configured, BigDecimal.ZERO);
            } else {
                WhiteboxEvaluation evaluation = evaluator.evaluate(context, rule);
                if (!evaluation.violated()) {
                    passCount++;
                    violation = new WhiteboxViolation(rule.ruleId(), WhiteboxStatus.PASS, label,
                            entry.description(), null, "Đạt", configured, BigDecimal.ZERO);
                } else if (rule.severity() == WhiteboxSeverity.DEDUCTION) {
                    failCount++;
                    BigDecimal penalty = computePenalty(rule, points);
                    rawDeduction = rawDeduction.add(penalty);
                    violation = new WhiteboxViolation(rule.ruleId(), WhiteboxStatus.FAIL, label,
                            entry.description(), evaluation.actual(), violationReason(label),
                            configured, penalty);
                } else {
                    warnCount++;
                    violation = new WhiteboxViolation(rule.ruleId(), WhiteboxStatus.WARN, label,
                            entry.description(), evaluation.actual(),
                            "Vi phạm (chỉ cảnh báo, không trừ điểm): " + label, configured, BigDecimal.ZERO);
                }
            }

            violations.add(violation);
            if (emitTrace && violation.isDetailed()) {
                emitDetail(violation, rule);
            }
            if (effectiveSettings.stopOnFirstViolation() && violation.status() == WhiteboxStatus.FAIL) {
                break;
            }
        }

        BigDecimal cap = effectiveSettings.effectiveCap(points);
        BigDecimal capped = cap == null ? rawDeduction : rawDeduction.min(cap);
        rawDeduction = scaleNonNegative(rawDeduction);
        capped = scaleNonNegative(capped);

        if (emitTrace) {
            emitSummary(passCount, failCount, warnCount, unverifiedCount, capped);
        }
        return new WhiteboxResult(violations, rawDeduction, capped, facts.parseOk());
    }

    private BigDecimal computePenalty(WhiteboxRule rule, BigDecimal points) {
        BigDecimal value = rule.penaltyValueOrZero();
        if (value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal penalty = rule.penaltyUnit() == WhiteboxPenaltyUnit.PERCENTAGE_OF_QUESTION
                ? points.multiply(value).divide(HUNDRED, 4, RoundingMode.HALF_UP)
                : value;
        return penalty.max(BigDecimal.ZERO);
    }

    private static BigDecimal scaleNonNegative(BigDecimal value) {
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        return scaled.signum() < 0 ? BigDecimal.ZERO : scaled;
    }

    private static String violationReason(String label) {
        return "Vi phạm: " + label;
    }

    private void emitDetail(WhiteboxViolation v, WhiteboxRule rule) {
        if (!GradingTraceCollector.isActive()) {
            return;
        }
        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_WHITEBOX_CHECK,
                v.status().traceStatus(),
                "[Whitebox] " + v.label(),
                v.reason(),
                null,
                null,
                TRACE_RULE_TARGET,
                v.ruleId(),
                rule.severity() == null ? null : rule.severity().name(),
                v.configuredPenalty(),
                null,
                null,
                v.deductedPoints(),
                v.expected(),
                v.actual(),
                TRACE_CONFIG_SUMMARY));
    }

    private void emitSummary(int pass, int fail, int warn, int unverified, BigDecimal capped) {
        if (!GradingTraceCollector.isActive()) {
            return;
        }
        int total = pass + fail + warn + unverified;
        String message = String.format(
                "Whitebox: %d rule — %d PASS, %d FAIL, %d WARN, %d UNVERIFIED; tổng trừ %s đ",
                total, pass, fail, warn, unverified, capped.toPlainString());
        GradingTraceCollector.add(new GradingTraceItem(
                GradingTraceItem.KIND_WHITEBOX_CHECK,
                GradingTraceItem.STATUS_INFO,
                "[Whitebox] Tổng kết",
                message,
                null, null, TRACE_RULE_TARGET, null, null, null, null, null,
                capped, null, null, TRACE_CONFIG_SUMMARY));
    }
}
