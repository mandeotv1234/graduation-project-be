package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.TeacherNotificationResponse;

import java.time.LocalDateTime;

public record TeacherNotificationResponseDto(
        Long id,
        Long teacherId,
        Long examId,
        Long studentId,
        String studentName,
        String violationType,
        String description,
        long violationCount,
        boolean autoSubmitted,
        boolean isRead,
        LocalDateTime createdAt) {

    public static TeacherNotificationResponseDto fromResponse(TeacherNotificationResponse r) {
        return new TeacherNotificationResponseDto(
                r.id(), r.teacherId(), r.examId(), r.studentId(),
                r.studentName(), r.violationType(), r.description(),
                r.violationCount(), r.autoSubmitted(), r.isRead(),
                r.createdAt());
    }
}
