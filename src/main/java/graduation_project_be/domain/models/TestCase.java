package graduation_project_be.domain.models;

import graduation_project_be.domain.models.enums.MatchType;
import graduation_project_be.domain.models.enums.VerificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCase {
    private Long id;
    private Long questionId;

    /** SQL to read the actual outcome of running the routine. Engine may auto-build
     *  this from invocationQuery + verificationType when populating from AI rubric. */
    private String validationQuery;

    /** Captured result of running validation_query against the teacher's correctQuery
     *  on a sandbox during question creation. NEVER trusted from raw AI output. */
    private String expectedValue;

    /** Weight in [0,1]. Sum of all TC weights for a question is normalized to 1.0
     *  inside gradeByTestCases when applying it back to the question's totalPoints. */
    private BigDecimal scoreWeight;

    private Integer orderIndex;

    // ---- Fields added in T04 to support full verification_type support ----

    /** Short Vietnamese description shown to students in error reports. */
    private String caseName;

    /** SQL to seed test data (CREATE/INSERT temp tables) before invocation. Run inside
     *  a transaction that is rolled back after the test case so TC's don't bleed state. */
    private String setupScript;

    /** SQL to actually call/fire the routine. For Function this is implicit (built from
     *  inputParameters). For SP this is "EXEC sp_X @p=...". For Trigger this is the
     *  INSERT/UPDATE/DELETE that fires the trigger. */
    private String invocationQuery;

    /** Drives how validation_query is built and where engine reads the actual result. */
    private VerificationType verificationType;

    /** JSON describing parameters: e.g. {"customerId": 1, "newStatus": "SHIPPED"}.
     *  Engine substitutes into validation_query / invocation_query templates. */
    private String inputParameters;

    /** EXACT (default) | CONTAINS. */
    private MatchType matchType;
}
