package graduation_project_be.application.usecases.response;

import java.time.LocalDateTime;

public record StartExamSessionResponse(
        boolean sessionStarted,
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
        return new StartExamSessionResponse(true, "Exam session started successfully",
                serverTime, examStartedAt, examEndTime, remainingSeconds, durationMinutes);
    }

    public static StartExamSessionResponse conflict() {
        return new StartExamSessionResponse(false,
                "Your account is already taking this exam on another device. Please close the other session first.",
                null, null, null, 0, 0);
    }
}
