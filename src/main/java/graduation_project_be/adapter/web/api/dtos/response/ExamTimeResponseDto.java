package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.ExamTimeResponse;

import java.time.LocalDateTime;

public record ExamTimeResponseDto(
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

    public static ExamTimeResponseDto fromResponse(ExamTimeResponse r) {
        return new ExamTimeResponseDto(
                r.examId(), r.serverTime(), r.examStartTime(),
                r.examEndTime(), r.studentStartedAt(),
                r.remainingSeconds(), r.secondsUntilStart(),
                r.durationMinutes(), r.status(), r.expired());
    }
}
