package graduation_project_be.application.usecases.request;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * Stateless white-box validation input: run the supplied rules against the supplied SQL (model answer,
 * arbitrary preview, or a single rule preview). No DB access, no black-box grading.
 */
public record WhiteboxValidateRequest(
        String questionType,
        String sql,
        JsonNode whiteboxRules,
        JsonNode whiteboxSettings,
        BigDecimal questionPoints) {
}
