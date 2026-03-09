package graduation_project_be.application.usecases.response;

import java.time.LocalDateTime;

public record ExamTimeResponse(
        Long examId,
        LocalDateTime serverTime,
        LocalDateTime examStartTime,
        LocalDateTime examEndTime,
        LocalDateTime studentStartedAt,
        long remainingSeconds,
        long secondsUntilStart,
        int durationMinutes,
        String status,
        boolean expired) {
}
