package graduation_project_be.application.usecases.grading;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/** Normalizes test-case weights while preserving explicit zero-weight cases. */
public final class TestCaseWeightNormalizer {

    private static final MathContext NORMALIZATION_CONTEXT = MathContext.DECIMAL128;

    private TestCaseWeightNormalizer() {
    }

    public static List<BigDecimal> normalize(List<BigDecimal> rawWeights) {
        if (rawWeights == null || rawWeights.isEmpty()) {
            return List.of();
        }

        List<BigDecimal> effectiveWeights = new ArrayList<>(rawWeights.size());
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (BigDecimal rawWeight : rawWeights) {
            BigDecimal effectiveWeight = rawWeight == null ? BigDecimal.ZERO : rawWeight.abs();
            effectiveWeights.add(effectiveWeight);
            totalWeight = totalWeight.add(effectiveWeight);
        }

        if (totalWeight.signum() == 0) {
            effectiveWeights.replaceAll(ignored -> BigDecimal.ONE);
            totalWeight = BigDecimal.valueOf(effectiveWeights.size());
        }

        int residualIndex = lastPositiveIndex(effectiveWeights);
        List<BigDecimal> normalizedWeights = new ArrayList<>(effectiveWeights.size());
        for (int index = 0; index < effectiveWeights.size(); index++) {
            normalizedWeights.add(BigDecimal.ZERO);
        }

        BigDecimal assignedWeight = BigDecimal.ZERO;
        for (int index = 0; index < effectiveWeights.size(); index++) {
            BigDecimal effectiveWeight = effectiveWeights.get(index);
            if (index == residualIndex || effectiveWeight.signum() == 0) {
                continue;
            }
            BigDecimal normalizedWeight = effectiveWeight.divide(totalWeight, NORMALIZATION_CONTEXT);
            normalizedWeights.set(index, normalizedWeight);
            assignedWeight = assignedWeight.add(normalizedWeight);
        }
        normalizedWeights.set(residualIndex, BigDecimal.ONE.subtract(assignedWeight));

        return List.copyOf(normalizedWeights);
    }

    private static int lastPositiveIndex(List<BigDecimal> weights) {
        for (int index = weights.size() - 1; index >= 0; index--) {
            if (weights.get(index).signum() > 0) {
                return index;
            }
        }
        throw new IllegalArgumentException("At least one effective weight is required");
    }
}
