package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.TestCase;
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

    public TestCase toModel() {
        return TestCase.builder()
                .id(id)
                .questionId(questionId)
                .validationQuery(validationQuery)
                .expectedValue(expectedValue)
                .scoreWeight(scoreWeight)
                .orderIndex(orderIndex)
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
                .build();
    }
}
