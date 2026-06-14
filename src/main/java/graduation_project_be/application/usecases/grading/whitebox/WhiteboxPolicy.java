package graduation_project_be.application.usecases.grading.whitebox;

/**
 * How a teacher judges a detected SQL feature. This is the teacher-facing authoring axis: pick a
 * feature, then pick one allowed policy over it. {@link WhiteboxRuleType} stays as the
 * backward-compatible persisted/grading field; policy is the additive authoring metadata.
 */
public enum WhiteboxPolicy {
    FORBID("Cấm"),
    REQUIRE("Bắt buộc"),
    AT_MOST("Tối đa"),
    AT_LEAST("Tối thiểu"),
    EXACTLY("Đúng bằng"),
    REQUIRE_ANY("Bắt buộc (bất kỳ)"),
    REQUIRE_ALL("Bắt buộc (tất cả)"),
    FORBID_ANY("Cấm (bất kỳ)");

    private final String label;

    WhiteboxPolicy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
