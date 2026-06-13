package graduation_project_be.application.usecases.grading.whitebox;

import graduation_project_be.application.usecases.grading.QueryStructureFacts;

/**
 * Per-answer input shared by every SELECT white-box evaluator.
 *
 * @param cleanedSql SQL stripped of comments / string literals / bracket identifiers (text-safe checks)
 * @param rawSql     the original student SQL
 * @param facts      JSQLParser structural facts; {@code facts.parseOk()} false => parser-dependent rules UNVERIFIED
 */
public record SelectWhiteboxContext(String cleanedSql, String rawSql, QueryStructureFacts facts) {
}
