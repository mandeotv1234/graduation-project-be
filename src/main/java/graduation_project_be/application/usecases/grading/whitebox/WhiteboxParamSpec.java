package graduation_project_be.application.usecases.grading.whitebox;

/**
 * Describes one configurable parameter of a catalog rule, so the frontend can render the right input
 * without hardcoding rule definitions.
 *
 * @param name         JSON key inside {@code rule.params} (e.g. max_depth, keywords, functions)
 * @param type         input kind: NUMBER or STRING_LIST
 * @param label        human label (Vietnamese)
 * @param required     whether the rule needs this param to evaluate meaningfully
 * @param defaultValue suggested default (Integer for NUMBER, may be null)
 */
public record WhiteboxParamSpec(
        String name,
        String type,
        String label,
        boolean required,
        Object defaultValue) {

    public static final String TYPE_NUMBER = "NUMBER";
    public static final String TYPE_STRING_LIST = "STRING_LIST";
    public static final String TYPE_STRING = "STRING";

    public static WhiteboxParamSpec number(String name, String label, boolean required, Integer defaultValue) {
        return new WhiteboxParamSpec(name, TYPE_NUMBER, label, required, defaultValue);
    }

    public static WhiteboxParamSpec stringList(String name, String label, boolean required) {
        return new WhiteboxParamSpec(name, TYPE_STRING_LIST, label, required, null);
    }

    public static WhiteboxParamSpec string(String name, String label, boolean required) {
        return new WhiteboxParamSpec(name, TYPE_STRING, label, required, null);
    }
}
