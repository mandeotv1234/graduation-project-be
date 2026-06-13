package graduation_project_be.application.usecases;

import graduation_project_be.application.usecases.grading.whitebox.WhiteboxEngine;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxResult;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxRubricParser;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxRule;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxSettings;
import graduation_project_be.application.usecases.request.WhiteboxValidateRequest;
import graduation_project_be.application.usecases.response.WhiteboxValidateResponse;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Stateless white-box validation: runs the supplied rules/settings against the supplied SQL through
 * the shared engine (no trace emission, no DB). Used for model-answer validation, supplied-answer
 * preview, and single-rule preview. Never blocks anything — it only reports.
 */
@RequiredArgsConstructor
public class WhiteboxValidateUsecase {

    private static final String DEFAULT_QUESTION_TYPE = "SELECT_QUERY";

    private final WhiteboxEngine whiteboxEngine;

    public WhiteboxValidateResponse execute(WhiteboxValidateRequest request) {
        String questionType = (request.questionType() == null || request.questionType().isBlank())
                ? DEFAULT_QUESTION_TYPE
                : request.questionType().trim().toUpperCase(java.util.Locale.ROOT);
        List<WhiteboxRule> rules = WhiteboxRubricParser.parseRules(request.whiteboxRules());
        WhiteboxSettings settings = WhiteboxRubricParser.parseSettings(request.whiteboxSettings());
        WhiteboxResult result = whiteboxEngine.evaluate(
                questionType, request.sql(), rules, settings, request.questionPoints(), false);
        return WhiteboxValidateResponse.fromResult(result);
    }
}
