package graduation_project_be.application.usecases.grading.whitebox;

/**
 * Unit for a white-box rule penalty.
 *
 * <ul>
 *   <li>{@code ABSOLUTE} — penalty_value is points subtracted directly.</li>
 *   <li>{@code PERCENTAGE_OF_QUESTION} — penalty_value is a percentage of the question's max points.</li>
 * </ul>
 */
public enum WhiteboxPenaltyUnit {
    ABSOLUTE,
    PERCENTAGE_OF_QUESTION
}
