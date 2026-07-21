package graduation_project_be.application.usecases.grading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCaseWeightNormalizerTest {

    @Test
    void normalizesRoundedThirdsToAnExactTotalOfOne() {
        List<BigDecimal> normalized = TestCaseWeightNormalizer.normalize(List.of(
                new BigDecimal("0.33"),
                new BigDecimal("0.33"),
                new BigDecimal("0.33")));

        assertEquals(0, BigDecimal.ONE.compareTo(sum(normalized)));
        assertEquals(0, normalized.get(0).compareTo(normalized.get(1)));
        assertTrue(normalized.get(2).compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void preservesExplicitZeroWhenAnotherWeightIsPositive() {
        List<BigDecimal> normalized = TestCaseWeightNormalizer.normalize(List.of(
                BigDecimal.ONE,
                BigDecimal.ZERO));

        assertEquals(0, BigDecimal.ONE.compareTo(normalized.get(0)));
        assertEquals(0, BigDecimal.ZERO.compareTo(normalized.get(1)));
    }

    @Test
    void distributesWeightEquallyWhenAllWeightsAreZero() {
        List<BigDecimal> normalized = TestCaseWeightNormalizer.normalize(List.of(
                BigDecimal.ZERO,
                BigDecimal.ZERO));

        assertEquals(0, new BigDecimal("0.5").compareTo(normalized.get(0)));
        assertEquals(0, new BigDecimal("0.5").compareTo(normalized.get(1)));
        assertEquals(0, BigDecimal.ONE.compareTo(sum(normalized)));
    }

    @Test
    void usesAbsoluteValuesForNegativeWeights() {
        List<BigDecimal> normalized = TestCaseWeightNormalizer.normalize(List.of(
                new BigDecimal("-2"),
                BigDecimal.ONE));

        BigDecimal expectedFirst = new BigDecimal("2").divide(new BigDecimal("3"), MathContext.DECIMAL128);
        assertEquals(0, expectedFirst.compareTo(normalized.get(0)));
        assertEquals(0, BigDecimal.ONE.compareTo(sum(normalized)));
    }

    @Test
    void emptyInputReturnsEmptyOutput() {
        assertTrue(TestCaseWeightNormalizer.normalize(List.of()).isEmpty());
    }

    private BigDecimal sum(List<BigDecimal> weights) {
        return weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
