package graduation_project_be.application.usecases.request;

import graduation_project_be.domain.models.ExamSettings;

import java.time.LocalDateTime;

public record UpdateTeacherExamSettingsRequest(
        String title,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        String description,
        Integer maxAttempts,
        Integer lateThreshold,
        ExamSettings settings
) {
}
