package graduation_project_be.application.usecases.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record GetStudentProgressInClassResponse(
        Long classId,
        String classCode,
        String semester,
        StudentInfo student,
        List<ExamProgressItem> exams) {

    public record StudentInfo(
            Long id,
            String fullName,
            String email) {
    }

    public record ExamProgressItem(
            Long examId,
            String title,
            Integer durationMinutes,
            String gradingMethod,
            int attemptCount,
            LocalDateTime latestSubmittedAt,
            BigDecimal finalScore,
            BigDecimal maxScore,
            BigDecimal finalPercent,
            String status,
            Long selectedSubmissionId) {
    }
}
