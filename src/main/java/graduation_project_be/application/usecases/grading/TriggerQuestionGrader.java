package graduation_project_be.application.usecases.grading;

import graduation_project_be.application.port.repositories.*;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSubmission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.util.*;

/** Grades TRIGGER questions (falls back to routine grading for the trigger body). */
@Slf4j
@RequiredArgsConstructor
public class TriggerQuestionGrader {

    private final TestCaseRepository testCaseRepository;
    private final GradingSupport support;
    private final RoutineQuestionGrader routineGrader;

    /**
     * Grades a TRIGGER question using the same model as SELECT:
     * <ul>
     *   <li>Black-box: run the test cases (setup/invoke/validate) via
     *       {@link GradingSupport#gradeByTestCases}.</li>
     *   <li>Score = {@code totalPoints × normalized test-case ratio} — the full
     *       question points are the base (no 20/80 metadata/test-case split
     *       anymore).</li>
     *   <li>White-box is applied afterwards by {@code GradeExamUsecase} on top of
     *       this black-box score.</li>
     * </ul>
     * Trigger metadata (name, table, event, timing) is no longer graded or
     * emitted — grading is driven purely by test cases and white-box rules.
     */
    public boolean gradeTriggerAlgorithmic(String schemaName, String teacherSchemaName, ExamQuestion question,
            ExamSubmission submission) {
        // T11/T12: setup_script applied per-test-case inside a transaction.
        // See note in gradeRoutineAlgorithmic — same reasoning applies here.

        BigDecimal totalPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        boolean hasTestCases = !testCaseRepository.findByQuestionId(question.getId()).isEmpty();

        // No test cases: delegate to support, which fail-louds (missing rubric)
        // and falls back to strict comparison when possible.
        if (!hasTestCases) {
            return support.gradeByTestCases(schemaName, teacherSchemaName, question, submission);
        }

        boolean testCasesPassed = support.gradeByTestCases(schemaName, teacherSchemaName, question, submission);
        // gradeByTestCases sets scoreEarned as a normalized test-case ratio
        // (range 0..1). Scale by the FULL question points — same formula as the
        // SP-only path in gradeRoutineAlgorithmic and as SELECT. The previous
        // 0.8 test-case weight (and 0.2 metadata weight) is gone.
        if (submission != null && submission.getScoreEarned() != null) {
            BigDecimal scaled = totalPoints.multiply(submission.getScoreEarned());
            if (scaled.compareTo(totalPoints) > 0) {
                scaled = totalPoints;
            }
            if (scaled.compareTo(BigDecimal.ZERO) < 0) {
                scaled = BigDecimal.ZERO;
            }
            submission.setScoreEarned(scaled);
        }

        return testCasesPassed;
    }
}
