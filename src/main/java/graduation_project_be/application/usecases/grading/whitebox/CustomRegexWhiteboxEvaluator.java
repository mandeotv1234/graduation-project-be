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

    private CustomRegexWhiteboxEvaluator() {
    }

    static boolean isCustomRegexRule(WhiteboxRule rule) {
        return rule != null && rule.ruleId() != null && rule.ruleId().startsWith(PREFIX);
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
        Matcher matcher = compiled.matcher(sql);
        boolean matched = matcher.find();
        String actual = matched ? excerpt(matcher.group()) : null;
        boolean violated = "FORBID".equals(policy) ? matched : !matched;
        if (!violated && matched) {
            actual = null;
        }
        return CustomRegexResult.valid(new WhiteboxEvaluation(violated, actual));
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
