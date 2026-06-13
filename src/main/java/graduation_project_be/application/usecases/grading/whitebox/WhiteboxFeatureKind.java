package graduation_project_be.application.usecases.grading.whitebox;

/**
 * The signal shape an evaluator reads, so the frontend knows what authoring controls to render for a
 * catalog entry.
 *
 * <ul>
 *   <li>{@code BOOLEAN} — presence/absence of a construct (no params).</li>
 *   <li>{@code NUMERIC} — a count/depth compared against a numeric threshold param.</li>
 *   <li>{@code SET} — matched against a teacher-supplied list (functions / keywords / operators).</li>
 *   <li>{@code COMPOSITE} — derived from several structural facts; reserved for complex rules.</li>
 * </ul>
 */
public enum WhiteboxFeatureKind {
    BOOLEAN,
    NUMERIC,
    SET,
    COMPOSITE
}
