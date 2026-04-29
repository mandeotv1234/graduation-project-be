package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.TestCase;
import graduation_project_be.domain.models.enums.MatchType;
import graduation_project_be.domain.models.enums.VerificationType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Table(name = "test_cases")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "validation_query", nullable = false, columnDefinition = "TEXT")
    private String validationQuery;

    @Column(name = "expected_value", nullable = false, columnDefinition = "TEXT")
    private String expectedValue;

    @Column(name = "score_weight", nullable = false)
    private BigDecimal scoreWeight;

    @Column(name = "order_index")
    private Integer orderIndex;

    // ---- Added in T04 (see liquibase changelog T15) ----

    @Column(name = "case_name", columnDefinition = "TEXT")
    private String caseName;

    @Column(name = "setup_script", columnDefinition = "TEXT")
    private String setupScript;

    @Column(name = "invocation_query", columnDefinition = "TEXT")
    private String invocationQuery;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_type", length = 32)
    private VerificationType verificationType;

    @Column(name = "input_parameters", columnDefinition = "TEXT")
    private String inputParameters;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", length = 16)
    private MatchType matchType;

    public TestCase toModel() {
        return TestCase.builder()
                .id(id)
                .questionId(questionId)
                .validationQuery(validationQuery)
                .expectedValue(expectedValue)
                .scoreWeight(scoreWeight)
                .orderIndex(orderIndex)
                .caseName(caseName)
                .setupScript(setupScript)
                .invocationQuery(invocationQuery)
                .verificationType(verificationType)
                .inputParameters(inputParameters)
                .matchType(matchType)
                .build();
    }

    public static TestCaseEntity fromModel(TestCase model) {
        return TestCaseEntity.builder()
                .id(model.getId())
                .questionId(model.getQuestionId())
                .validationQuery(model.getValidationQuery())
                .expectedValue(model.getExpectedValue())
                .scoreWeight(model.getScoreWeight())
                .orderIndex(model.getOrderIndex())
                .caseName(model.getCaseName())
                .setupScript(model.getSetupScript())
                .invocationQuery(model.getInvocationQuery())
                .verificationType(model.getVerificationType())
                .inputParameters(model.getInputParameters())
                .matchType(model.getMatchType())
                .build();
    }
}
