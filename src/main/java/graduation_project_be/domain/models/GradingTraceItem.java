package graduation_project_be.domain.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GradingTraceItem(
    String kind,
    String status,
    String label,
    String message,
    String caseId,
    String caseName,
    String ruleTarget,
    String ruleCondition,
    String action,
    BigDecimal configuredPenalty,
    BigDecimal earnedPoints,
    BigDecimal maxPoints,
    BigDecimal deductedPoints,
    String expected,
    String actual,
    String configSummary
) {
    // Kind constants
    public static final String KIND_TEST_CASE = "TEST_CASE";
    public static final String KIND_RUBRIC_RULE = "RUBRIC_RULE";
    public static final String KIND_METADATA_CHECK = "METADATA_CHECK";
    public static final String KIND_EXECUTION_ERROR = "EXECUTION_ERROR";
    public static final String KIND_TEACHER_CONFIG = "TEACHER_CONFIG";
    public static final String KIND_SUMMARY = "SUMMARY";
    // White-box method check (grading_payload.whitebox_rules); ruleCondition carries the rule_id.
    public static final String KIND_WHITEBOX_CHECK = "WHITEBOX_CHECK";

    // Status constants
    public static final String STATUS_PASS = "PASS";
    public static final String STATUS_FAIL = "FAIL";
    public static final String STATUS_WARN = "WARN";
    public static final String STATUS_INFO = "INFO";
    // A parser-dependent white-box check whose SQL could not be parsed: deducts 0, not a pass.
    public static final String STATUS_UNVERIFIED = "UNVERIFIED";
}
