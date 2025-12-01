package graduation_project_be.infrastructure.errors;

import com.fasterxml.jackson.annotation.JsonInclude;
import graduation_project_be.application.codes.Code;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String message, Code code, List<FieldErrorDetail> details) {
}