package graduation_project_be.application.usecases.grading.whitebox;

import graduation_project_be.domain.models.GradingTraceItem;

import java.util.List;

/**
 * Builds the student-facing white-box trace from the full stored trace. Students see only the
 * concise reason and deduction of rules that actually cost points (FAIL); matched SQL evidence
 * ({@code expected}/{@code actual}) is stripped so the model answer is never leaked, and
 * WARN/UNVERIFIED/PASS internals stay teacher-only.
 */
public final class WhiteboxStudentTrace {

    private WhiteboxStudentTrace() {
    }

    public static List<GradingTraceItem> concise(List<GradingTraceItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(it -> GradingTraceItem.KIND_WHITEBOX_CHECK.equals(it.kind()))
                .filter(it -> GradingTraceItem.STATUS_FAIL.equals(it.status()))
                .map(WhiteboxStudentTrace::stripEvidence)
                .toList();
    }

    private static GradingTraceItem stripEvidence(GradingTraceItem it) {
        return new GradingTraceItem(
                it.kind(),
                it.status(),
                it.label(),
                it.message(),
                null,
                null,
                null,            // ruleTarget hidden from students
                null,            // ruleCondition (internal id) hidden from students
                null,
                null,            // configuredPenalty hidden; only the realised deduction matters
                null,
                null,
                it.deductedPoints(),
                null,            // expected (model-answer evidence) stripped
                null,            // actual (matched SQL evidence) stripped
                null);
    }
}
