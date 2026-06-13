package graduation_project_be.application.dto;

import graduation_project_be.domain.models.QuestionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public class RulePresetDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotBlank(message = "Tên mẫu không được để trống")
        private String name;

        @NotNull(message = "Loại câu hỏi không được để trống")
        private QuestionType questionType;

        @NotBlank(message = "Quy tắc không được để trống")
        private String rulesJson;

        // "BLACKBOX" or "WHITEBOX"; defaults to "BLACKBOX" if omitted
        private String kind;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private Long id;
        private Long teacherId;
        private String name;
        private QuestionType questionType;
        private String rulesJson;
        private String kind;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
