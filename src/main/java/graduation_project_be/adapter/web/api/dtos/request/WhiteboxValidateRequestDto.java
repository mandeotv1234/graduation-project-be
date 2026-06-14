package graduation_project_be.adapter.web.api.dtos.request;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.application.usecases.request.WhiteboxValidateRequest;
import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/exams/whitebox/validate}. {@code whiteboxRules} is the rule array
 * and {@code whiteboxSettings} the settings object, sent as raw JSON (same shape stored under
 * {@code grading_payload}). Stateless — no examId needed.
 */
public record WhiteboxValidateRequestDto(
        String questionType,
        String sql,
        JsonNode whiteboxRules,
        JsonNode whiteboxSettings,
        BigDecimal questionPoints) {

    public WhiteboxValidateRequest toRequest() {
        return new WhiteboxValidateRequest(questionType, sql, whiteboxRules, whiteboxSettings, questionPoints);
    }
}
