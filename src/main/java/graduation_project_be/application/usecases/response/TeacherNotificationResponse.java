package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.TeacherNotification;

import java.time.LocalDateTime;

public record TeacherNotificationResponse(
        Long id,
        Long teacherId,
        Long examId,
        Long resultId,
        Long studentId,
        String studentName,
        String violationType,
        String description,
        long violationCount,
        boolean autoSubmitted,
        boolean isRead,
        LocalDateTime createdAt) {

    public static TeacherNotificationResponse fromModel(TeacherNotification model) {
        return new TeacherNotificationResponse(
                model.getId(),
                model.getTeacherId(),
                model.getExamId(),
                model.getResultId(),
                model.getStudentId(),
                model.getStudentName(),
                model.getViolationType(),
                model.getDescription(),
                model.getViolationCount(),
                model.isAutoSubmitted(),
                model.isRead(),
                model.getCreatedAt());
    }
}
