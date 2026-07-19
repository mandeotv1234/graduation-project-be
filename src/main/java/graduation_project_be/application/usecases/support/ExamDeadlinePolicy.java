package graduation_project_be.application.usecases.support;

import graduation_project_be.domain.models.Exam;

import java.time.Duration;
import java.time.LocalDateTime;

public final class ExamDeadlinePolicy {

    private ExamDeadlinePolicy() {
    }

    public static ExamDeadlines calculate(Exam exam, LocalDateTime startedAt) {
        int durationMinutes = exam.getDurationMinutes() != null
                ? Math.max(0, exam.getDurationMinutes())
                : 0;

        LocalDateTime regularDeadline = startedAt.plusMinutes(durationMinutes);
        if (exam.getEndTime() != null && exam.getEndTime().isBefore(regularDeadline)) {
            regularDeadline = exam.getEndTime();
        }

        int lateThresholdMinutes = resolveLateThresholdMinutes(exam);
        LocalDateTime submissionDeadline = regularDeadline.plusMinutes(lateThresholdMinutes);

        return new ExamDeadlines(regularDeadline, submissionDeadline, lateThresholdMinutes);
    }

    public static long remainingSeconds(LocalDateTime now, LocalDateTime deadline) {
        long remainingMillis = Duration.between(now, deadline).toMillis();
        if (remainingMillis <= 0) {
            return 0;
        }
        return (remainingMillis + 999) / 1000;
    }

    private static int resolveLateThresholdMinutes(Exam exam) {
        boolean allowOvertime = exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getAllowOvertime());
        if (!allowOvertime || exam.getLateThreshold() == null) {
            return 0;
        }
        return Math.max(0, exam.getLateThreshold());
    }

    public record ExamDeadlines(
            LocalDateTime regularDeadline,
            LocalDateTime submissionDeadline,
            int lateThresholdMinutes) {

        public boolean isRegularTime(LocalDateTime now) {
            return now.isBefore(regularDeadline);
        }

        public boolean isLateSubmissionTime(LocalDateTime now) {
            return lateThresholdMinutes > 0
                    && !now.isBefore(regularDeadline)
                    && now.isBefore(submissionDeadline);
        }

        public boolean isExpired(LocalDateTime now) {
            return !now.isBefore(submissionDeadline);
        }

        public LocalDateTime activeDeadline(LocalDateTime now) {
            return isRegularTime(now) ? regularDeadline : submissionDeadline;
        }
    }
}
