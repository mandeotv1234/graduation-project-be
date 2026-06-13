package graduation_project_be.application.usecases.grading.whitebox;

/**
 * A rule-specific check selected by {@code questionType + rule_id}. Encodes the FORBIDDEN/REQUIRED/
 * LIMIT polarity itself, returning {@code violated=true} when the rule fails for the supplied SQL.
 *
 * <p>Parser dependence is declared on the catalog entry, not here: the engine maps a parser-dependent
 * rule to {@code UNVERIFIED} before this evaluator runs when the SQL did not parse.
 */
@FunctionalInterface
public interface WhiteboxRuleEvaluator {

    WhiteboxEvaluation evaluate(SelectWhiteboxContext context, WhiteboxRule rule);
}
