package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetStudentProgressInClassResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record GetStudentProgressInClassResponseDto(
        Long classId,
        String classCode,
        String semester,
        StudentInfoDto student,
        List<ExamProgressItemDto> exams) {

    public record StudentInfoDto(
            Long id,
            String fullName,
            String email) {
        public static StudentInfoDto fromResponse(GetStudentProgressInClassResponse.StudentInfo item) {
            return new StudentInfoDto(item.id(), item.fullName(), item.email());
        }
    }

    public record ExamProgressItemDto(
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
        public static ExamProgressItemDto fromResponse(GetStudentProgressInClassResponse.ExamProgressItem item) {
            return new ExamProgressItemDto(
                    item.examId(),
                    item.title(),
                    item.durationMinutes(),
                    item.gradingMethod(),
                    item.attemptCount(),
                    item.latestSubmittedAt(),
                    item.finalScore(),
                    item.maxScore(),
                    item.finalPercent(),
                    item.status(),
                    item.selectedSubmissionId());
        }
    }

    public static GetStudentProgressInClassResponseDto fromResponse(GetStudentProgressInClassResponse response) {
        return new GetStudentProgressInClassResponseDto(
                response.classId(),
                response.classCode(),
                response.semester(),
                StudentInfoDto.fromResponse(response.student()),
                response.exams().stream()
                        .map(ExamProgressItemDto::fromResponse)
                        .toList());
    }
}
