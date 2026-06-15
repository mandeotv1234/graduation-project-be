package graduation_project_be.application.usecases.grading.whitebox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Backend-owned source of truth for supported white-box rules and their evaluators. v1 registers the
 * SELECT_QUERY rule set only; non-SELECT question types are intentionally absent until a teammate
 * adds real evaluators. Frontend renders {@link #entriesFor} and never hardcodes a parallel catalog.
 */
public class WhiteboxCatalog {

    private final Map<String, WhiteboxCatalogEntry> entries = new LinkedHashMap<>();
    private final Map<String, WhiteboxRuleEvaluator> evaluators = new LinkedHashMap<>();

    public WhiteboxCatalog() {
        SelectWhiteboxRuleSet.registerInto(entries, evaluators);
        FunctionWhiteboxRuleSet.registerInto(entries, evaluators);
        StoredProcedureWhiteboxRuleSet.registerInto(entries, evaluators);
    }

    /** Catalog entries applicable to one question type (insertion order preserved). */
    public List<WhiteboxCatalogEntry> entriesFor(String questionType) {
        return entries.values().stream()
                .filter(e -> e.questionTypes().contains(questionType))
                .toList();
    }

    public WhiteboxCatalogEntry entry(String ruleId) {
        return ruleId == null ? null : entries.get(ruleId.toUpperCase(Locale.ROOT));
    }

    public WhiteboxRuleEvaluator evaluator(String ruleId) {
        return ruleId == null ? null : evaluators.get(ruleId.toUpperCase(Locale.ROOT));
    }

    public boolean supports(String ruleId) {
        return entry(ruleId) != null;
    }
}
