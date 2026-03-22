package graduation_project_be.domain.models;

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
    private String validationQuery;
    private String expectedValue;
    private BigDecimal scoreWeight;
    private Integer orderIndex;
}
