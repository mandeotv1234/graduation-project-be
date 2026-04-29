package graduation_project_be.domain.models.enums;

/**
 * Defines how engine compares actual vs expected for a test case.
 * Default is EXACT for backward compatibility with existing test_cases rows.
 */
public enum MatchType {
    /** Strict equality after trim + case-insensitive compare. */
    EXACT,

    /** Actual contains expected as a substring (useful for PRINT format tolerance). */
    CONTAINS
}
