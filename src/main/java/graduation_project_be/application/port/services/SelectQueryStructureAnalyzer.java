package graduation_project_be.application.port.services;

import graduation_project_be.application.usecases.grading.QueryStructureFacts;

/**
 * Parses a single SELECT statement and reports its structural facts (white-box grading input).
 *
 * <p>Implementations MUST NOT throw: any parse failure (dialect gap, syntax error, non-SELECT)
 * is reported as {@link QueryStructureFacts#parseFailed()} so the grader can fall back to
 * black-box result comparison instead of penalising the student.
 */
public interface SelectQueryStructureAnalyzer {

    QueryStructureFacts analyze(String sql);
}
