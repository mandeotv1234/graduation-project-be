package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.ExamDeviceConflict;

import java.time.LocalDateTime;

public record DeviceConflictResponse(
        String conflictId,
        Long examId,
        Long studentId,
        String studentName,
        String studentEmail,
        String existingIpAddress,
        String existingUserAgent,
        String newIpAddress,
        String newUserAgent,
        LocalDateTime requestedAt
) {
    public static DeviceConflictResponse fromModel(ExamDeviceConflict conflict) {
        return new DeviceConflictResponse(
                conflict.getConflictId(),
                conflict.getExamId(),
                conflict.getStudentId(),
                conflict.getStudentName(),
                conflict.getStudentEmail(),
                conflict.getExistingIpAddress(),
                conflict.getExistingUserAgent(),
                conflict.getNewIpAddress(),
                conflict.getNewUserAgent(),
                conflict.getRequestedAt()
        );
    }
}
