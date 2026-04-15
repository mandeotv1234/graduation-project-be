package graduation_project_be.adapter.web.api.dtos.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import graduation_project_be.application.usecases.request.UpdateExamRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

/** Body có thể gửi từng phần; field null = không đổi. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateExamRequestDto(
        String title,
        /** null = không đổi; 0 = gỡ đặc tả; {@code > 0} = gắn đặc tả. */
        Long specificationId,
        @Positive(message = "Duration must be positive") Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        String description,
        @Positive(message = "Max attempts must be positive") Integer maxAttempts,
        Integer lateThreshold,
        @Valid ExamSettingsDto settings) {

    public UpdateExamRequest toRequest(Long examId) {
        return new UpdateExamRequest(
                examId,
                title,
                specificationId,
                durationMinutes,
                startTime,
                endTime,
                isPublished,
                description,
                maxAttempts,
                lateThreshold,
                settings != null ? settings.toModel() : null,
                null,
                null);
    }

    public UpdateExamRequest toRequest(Long examId, String pdfFilePath, String originalPdfFileName) {
        return new UpdateExamRequest(
                examId,
                title,
                specificationId,
                durationMinutes,
                startTime,
                endTime,
                isPublished,
                description,
                maxAttempts,
                lateThreshold,
                settings != null ? settings.toModel() : null,
                pdfFilePath,
                originalPdfFileName);
    }
}
