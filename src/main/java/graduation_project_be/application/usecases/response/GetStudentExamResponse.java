package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;

import java.time.Duration;
import java.time.LocalDateTime;

public record GetStudentExamResponse(
        Long examId,
        Long classId,
        String title,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime serverTime,
        String status,
        long secondsUntilStart,
        String description,
        Integer maxAttempts,
        Integer lateThreshold,
        ExamSettings settings
) {
    /**
     * Status values:
     * - WAITING: exam has not started yet (now < startTime)
     * - IN_PROGRESS: exam is currently active (startTime <= now <= endTime)
     * - ENDED: exam has ended (now > endTime)
     */
    public static GetStudentExamResponse fromModel(Exam exam) {
        LocalDateTime now = LocalDateTime.now();
        String status;
        long secondsUntilStart = 0;

        if (exam.getStartTime() != null && now.isBefore(exam.getStartTime())) {
            status = "WAITING";
            secondsUntilStart = Duration.between(now, exam.getStartTime()).getSeconds();
        } else if (exam.getEndTime() != null && now.isAfter(exam.getEndTime())) {
            status = "ENDED";
        } else {
            status = "IN_PROGRESS";
        }

        return new GetStudentExamResponse(
                exam.getId(),
                exam.getClassId(),
                exam.getTitle(),
                exam.getDurationMinutes(),
                exam.getStartTime(),
                exam.getEndTime(),
                now,
                status,
                secondsUntilStart,
                exam.getDescription(),
                exam.getMaxAttempts(),
                exam.getLateThreshold(),
                exam.getSettings()
        );
    }
}
