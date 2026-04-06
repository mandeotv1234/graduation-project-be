package graduation_project_be.application.usecases.response;

import java.time.LocalDateTime;

public record StartExamSessionResponse(
        boolean sessionStarted,
        boolean conflictPending,
        String conflictId,
        String message,
        LocalDateTime serverTime,
        LocalDateTime examStartedAt,
        LocalDateTime examEndTime,
        long remainingSeconds,
        int durationMinutes) {

    public static StartExamSessionResponse success(LocalDateTime serverTime,
            LocalDateTime examStartedAt,
            LocalDateTime examEndTime,
            long remainingSeconds,
            int durationMinutes) {
        return new StartExamSessionResponse(true, false, null, "Exam session started successfully",
                serverTime, examStartedAt, examEndTime, remainingSeconds, durationMinutes);
    }

    public static StartExamSessionResponse conflictPending(String conflictId, String message) {
        return new StartExamSessionResponse(false, true, conflictId, message,
                null, null, null, 0, 0);
    }

    public static StartExamSessionResponse conflict() {
        return new StartExamSessionResponse(false, false, null,
                "Your account is already taking this exam on another device. Please close the other session first.",
                null, null, null, 0, 0);
    }
}
