package graduation_project_be.infrastructure.errors;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String message, ErrorCode errorCode, List<FieldErrorDetail> details) {
}