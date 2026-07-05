package graduation_project_be.application.usecases.grading.whitebox;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates teacher-defined regex rules stored directly in grading_payload.whitebox_rules[].
 */
final class CustomRegexWhiteboxEvaluator {

    static final String PREFIX = "CUSTOM_REGEX_";
    static final int MAX_PATTERN_LENGTH = 500;
    static final int MAX_RULES_PER_QUESTION = 10;
    static final int MAX_SQL_SCAN_LENGTH = 20_000;

    private static final Pattern NESTED_QUANTIFIER_PATTERN = Pattern.compile(
            "\\((?:[^()\\\\]|\\\\.|\\[[^\\]]*]){0,120}[+*][^()]{0,120}\\)[+*?{]");
    private static final Pattern BACKREFERENCE_PATTERN = Pattern.compile("\\\\[1-9]");
    private static final Pattern LOOKAROUND_PATTERN = Pattern.compile("\\(\\?([=!]|<[=!])");

    private CustomRegexWhiteboxEvaluator() {
    }

    static boolean isCustomRegexRule(WhiteboxRule rule) {
        if (rule == null) {
            return false;
        }
        if (rule.type() == WhiteboxRuleType.CUSTOM_REGEX) {
            return true;
        }
        return rule.type() == null && rule.ruleId() != null && rule.ruleId().startsWith(PREFIX);
    }

    static CustomRegexResult evaluate(SelectWhiteboxContext context, WhiteboxRule rule) {
        String pattern = WhiteboxParams.stringParam(rule, "pattern", "");
        if (pattern.isBlank()) {
            return CustomRegexResult.invalid("Regex tùy chỉnh chưa có pattern.");
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            return CustomRegexResult.invalid(
                    "Regex tùy chỉnh vượt quá " + MAX_PATTERN_LENGTH + " ký tự.");
        }

        String safetyError = validateSafety(pattern);
        if (safetyError != null) {
            return CustomRegexResult.invalid(safetyError);
        }

        String policy = WhiteboxParams.stringParam(rule, "policy", "FORBID")
                .toUpperCase(Locale.ROOT);
        if (!"FORBID".equals(policy) && !"REQUIRE".equals(policy)) {
            return CustomRegexResult.invalid("Policy của regex tùy chỉnh chỉ hỗ trợ FORBID hoặc REQUIRE.");
        }

        int flags = Pattern.UNICODE_CASE;
        if (WhiteboxParams.boolParam(rule, "case_insensitive", true)) {
            flags |= Pattern.CASE_INSENSITIVE;
        }

        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            return CustomRegexResult.invalid("Regex tùy chỉnh chưa hợp lệ: " + e.getDescription());
        }

        String sql = context == null || context.cleanedSql() == null ? "" : context.cleanedSql();
        if (sql.length() > MAX_SQL_SCAN_LENGTH) {
            sql = sql.substring(0, MAX_SQL_SCAN_LENGTH);
        }
        Matcher matcher = compiled.matcher(sql);
        boolean matched = matcher.find();
        String actual = matched ? excerpt(matcher.group()) : null;
        boolean violated = "FORBID".equals(policy) ? matched : !matched;
        if (!violated && matched) {
            actual = null;
        }
        return CustomRegexResult.valid(new WhiteboxEvaluation(violated, actual));
    }

    private static String validateSafety(String pattern) {
        if (BACKREFERENCE_PATTERN.matcher(pattern).find()) {
            return "Regex tùy chỉnh không hỗ trợ backreference để tránh làm chậm hệ thống chấm.";
        }
        if (LOOKAROUND_PATTERN.matcher(pattern).find()) {
            return "Regex tùy chỉnh không hỗ trợ lookaround để tránh làm chậm hệ thống chấm.";
        }
        if (NESTED_QUANTIFIER_PATTERN.matcher(pattern).find()) {
            return "Regex tùy chỉnh có lượng từ lồng nhau, có nguy cơ làm chậm hệ thống chấm.";
        }
        return null;
    }

    private static String excerpt(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    record CustomRegexResult(boolean valid, WhiteboxEvaluation evaluation, String error) {

        static CustomRegexResult valid(WhiteboxEvaluation evaluation) {
            return new CustomRegexResult(true, evaluation, null);
        }

        static CustomRegexResult invalid(String error) {
            return new CustomRegexResult(false, null, error);
        }
    }
}
