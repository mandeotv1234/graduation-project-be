package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.StartExamSessionResponse;

import java.time.LocalDateTime;

public record StartExamSessionResponseDto(
        boolean sessionStarted,
        boolean conflictPending,
        String conflictId,
        String message,
        LocalDateTime serverTime,
        LocalDateTime examStartedAt,
        LocalDateTime examEndTime,
        long remainingSeconds,
        int durationMinutes) {

    public static StartExamSessionResponseDto fromResponse(StartExamSessionResponse r) {
        return new StartExamSessionResponseDto(
                r.sessionStarted(), r.conflictPending(), r.conflictId(),
                r.message(), r.serverTime(),
                r.examStartedAt(), r.examEndTime(),
                r.remainingSeconds(), r.durationMinutes());
    }
}
