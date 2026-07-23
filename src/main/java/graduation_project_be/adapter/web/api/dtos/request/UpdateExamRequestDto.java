package graduation_project_be.adapter.web.api.dtos.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import graduation_project_be.application.usecases.request.UpdateExamRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/** Body có thể gửi từng phần; field null = không đổi. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateExamRequestDto(
        @Size(min = 1, max = 255, message = "Title must contain 1 to 255 characters") String title,
        /** null = không đổi; 0 = gỡ đặc tả; {@code > 0} = gắn đặc tả. */
        Long specificationId,
        @Positive(message = "Duration must be positive") @Max(value = 240, message = "Duration must not exceed 240 minutes") Integer durationMinutes,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        String description,
        @Positive(message = "Max attempts must be positive")
        @Max(value = 99, message = "Max attempts must not exceed 99")
        Integer maxAttempts,
        @PositiveOrZero(message = "Late threshold must be positive or zero")
        @Max(value = 240, message = "Late threshold must not exceed 240 minutes")
        Integer lateThreshold,
        @Valid ExamSettingsDto settings,
        /** true = gỡ file PDF hiện tại (đặc tả spec vẫn giữ nguyên nếu có). */
        Boolean removePdf) {

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
                null,
                removePdf);
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
                originalPdfFileName,
                removePdf);
    }
}
