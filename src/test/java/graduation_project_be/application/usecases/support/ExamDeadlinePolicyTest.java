package graduation_project_be.application.usecases.support;

import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExamDeadlinePolicyTest {

    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 7, 19, 8, 0);

    @Test
    void calculate_should_extend_submission_deadline_from_regular_deadline() {
        Exam exam = Exam.builder()
                .durationMinutes(60)
                .endTime(STARTED_AT.plusMinutes(45))
                .lateThreshold(10)
                .settings(ExamSettings.builder().allowOvertime(true).build())
                .build();

        var deadlines = ExamDeadlinePolicy.calculate(exam, STARTED_AT);

        assertThat(deadlines.regularDeadline()).isEqualTo(STARTED_AT.plusMinutes(45));
        assertThat(deadlines.submissionDeadline()).isEqualTo(STARTED_AT.plusMinutes(55));
        assertThat(deadlines.lateThresholdMinutes()).isEqualTo(10);
        assertThat(deadlines.isLateSubmissionTime(STARTED_AT.plusMinutes(50))).isTrue();
    }

    @Test
    void calculate_should_ignore_late_threshold_when_overtime_is_disabled() {
        Exam exam = Exam.builder()
                .durationMinutes(60)
                .lateThreshold(10)
                .settings(ExamSettings.builder().allowOvertime(false).build())
                .build();

        var deadlines = ExamDeadlinePolicy.calculate(exam, STARTED_AT);

        assertThat(deadlines.submissionDeadline()).isEqualTo(deadlines.regularDeadline());
        assertThat(deadlines.lateThresholdMinutes()).isZero();
        assertThat(deadlines.isExpired(STARTED_AT.plusMinutes(60))).isTrue();
    }

    @Test
    void remainingSeconds_should_round_up_partial_second() {
        LocalDateTime deadline = STARTED_AT.plusSeconds(1);

        assertThat(ExamDeadlinePolicy.remainingSeconds(
                STARTED_AT.plusNanos(100_000_000), deadline)).isEqualTo(1);
        assertThat(ExamDeadlinePolicy.remainingSeconds(deadline, deadline)).isZero();
    }
}
