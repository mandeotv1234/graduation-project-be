package graduation_project_be.domain.models.enums;

/**
 * Defines how a test case verifies a Stored Procedure / Function / Trigger.
 * Each value drives how the engine builds the validation query and where it
 * captures the actual result for comparison.
 */
public enum VerificationType {
    /** Function returning a scalar value. validation_query = "SELECT [schema].fn(args)". */
    RETURN_VALUE,

    /** Function returning a TABLE, or SP that emits a SELECT result set. Compare row-by-row. */
    RESULT_SET,

    /** SP with OUTPUT parameter. Engine wraps EXEC with DECLARE + SELECT @out. */
    OUT_PARAMETER,

    /** SP / Trigger whose effect is observable on a target table. validation_query reads from
     *  the affected table after invocation_query runs. */
    SIDE_EFFECT,

    /** Trigger/SP validation case where correctness is whether invocation succeeds or fails. */
    EXECUTION_STATUS,

    /** SP / Function that emits messages via PRINT. Captured from JDBC SQLWarning chain. */
    PRINT_OUTPUT
}
