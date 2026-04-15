package graduation_project_be.application.usecases.response;

import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import java.time.LocalDateTime;

public record GetTeacherExamDetailResponse(
        Long id,
        Long specificationId,
        Long classId,
        String title,
        Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        String description,
        Integer maxAttempts,
        Integer lateThreshold,
        ExamSettings settings,
        String pdfFilePath,
        String originalPdfFileName) {

    public static GetTeacherExamDetailResponse fromModel(Exam exam) {
        return new GetTeacherExamDetailResponse(
                exam.getId(),
                exam.getSpecificationId(),
                exam.getClassId(),
                exam.getTitle(),
                exam.getDurationMinutes(),
                exam.getStartTime(),
                exam.getEndTime(),
                exam.getIsPublished(),
                exam.getDescription(),
                exam.getMaxAttempts(),
                exam.getLateThreshold(),
                exam.getSettings(),
                exam.getPdfFilePath(),
                exam.getOriginalPdfFileName());
    }
}
