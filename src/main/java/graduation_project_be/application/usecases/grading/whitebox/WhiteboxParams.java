package graduation_project_be.application.usecases.grading.whitebox;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads typed values out of a rule's {@code params} JSON node. */
public final class WhiteboxParams {

    private WhiteboxParams() {
    }

    public static int intParam(WhiteboxRule rule, String name, int fallback) {
        JsonNode params = rule.params();
        if (params == null) {
            return fallback;
        }
        JsonNode node = params.path(name);
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.asInt(fallback);
        }
        try {
            return Integer.parseInt(node.asText().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Reads a string-list param. Accepts a JSON array (["TOP","NOLOCK"]) or a CSV string ("TOP,NOLOCK").
     * Tokens are trimmed and upper-cased; empties dropped.
     */
    public static List<String> stringList(WhiteboxRule rule, String name) {
        List<String> out = new ArrayList<>();
        JsonNode params = rule.params();
        if (params == null) {
            return out;
        }
        JsonNode node = params.path(name);
        if (node.isArray()) {
            for (JsonNode item : node) {
                addToken(out, item.asText(""));
            }
        } else if (node.isTextual()) {
            for (String token : node.asText("").split(",")) {
                addToken(out, token);
            }
        }
        return out;
    }

    private static void addToken(List<String> out, String raw) {
        String token = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!token.isEmpty() && !out.contains(token)) {
            out.add(token);
        }
    }
}
