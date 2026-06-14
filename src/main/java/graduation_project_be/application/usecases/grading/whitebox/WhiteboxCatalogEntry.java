package graduation_project_be.application.usecases.grading.whitebox;

import java.math.BigDecimal;
import java.util.List;

/**
 * Backend-owned definition of one supported white-box rule (the source of truth the frontend renders).
 * Serialised directly into the catalog API response; the matching evaluator is held separately in
 * {@link WhiteboxCatalog} and is not exposed.
 *
 * @param ruleId             UPPER_SNAKE_CASE id
 * @param type               FORBIDDEN / REQUIRED / LIMIT
 * @param group              UI grouping key (e.g. SUBQUERY_CTE, JOIN, SELECT_LIST)
 * @param label              Vietnamese label
 * @param description        Vietnamese description
 * @param defaultSeverity    recommended severity (WARNING_ONLY by default)
 * @param defaultPenaltyValue suggested penalty
 * @param defaultPenaltyUnit suggested unit
 * @param parserRequired     true if the check needs a successful parse (else UNVERIFIED on parse fail)
 * @param questionTypes      question types this rule applies to (SELECT_QUERY for v1)
 * @param params             configurable parameters
 * @param featureId          stable id of the SQL construct this rule judges (the FE groups by this)
 * @param featureLabel       Vietnamese display name of the feature
 * @param featureKind        evaluator signal shape (BOOLEAN / NUMERIC / SET / COMPOSITE)
 * @param policy             how the feature is judged (FORBID / REQUIRE / AT_MOST / ...)
 * @param policyLabel        Vietnamese display name of the policy
 * @param conflictsWith      rule ids that contradict this one (e.g. FORBIDDEN vs REQUIRED of one feature)
 */
public record WhiteboxCatalogEntry(
        String ruleId,
        WhiteboxRuleType type,
        String group,
        String label,
        String description,
        WhiteboxSeverity defaultSeverity,
        BigDecimal defaultPenaltyValue,
        WhiteboxPenaltyUnit defaultPenaltyUnit,
        boolean parserRequired,
        List<String> questionTypes,
        List<WhiteboxParamSpec> params,
        String featureId,
        String featureLabel,
        WhiteboxFeatureKind featureKind,
        WhiteboxPolicy policy,
        String policyLabel,
        List<String> conflictsWith) {
}
