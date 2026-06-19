package graduation_project_be.application.usecases.response;

import java.util.List;
import java.util.Map;

public record RubricTestGradeResponse(
        double earnedPoints,
        double totalPoints,
        boolean allPassed,
        List<Map<String, Object>> details,
        Double totalDeductions,
        Double blackboxScore,
        Double whiteboxDeduction) {

    public static RubricTestGradeResponse of(
            double earnedPoints,
            double totalPoints,
            boolean allPassed,
            List<Map<String, Object>> details) {
        return new RubricTestGradeResponse(earnedPoints, totalPoints, allPassed, details, null, null, null);
    }

    public static RubricTestGradeResponse of(
            double earnedPoints,
            double totalPoints,
            boolean allPassed,
            List<Map<String, Object>> details,
            Double totalDeductions) {
        return new RubricTestGradeResponse(earnedPoints, totalPoints, allPassed, details,
                totalDeductions, null, null);
    }

    public static RubricTestGradeResponse of(
            double earnedPoints,
            double totalPoints,
            boolean allPassed,
            List<Map<String, Object>> details,
            Double totalDeductions,
            Double blackboxScore,
            Double whiteboxDeduction) {
        return new RubricTestGradeResponse(earnedPoints, totalPoints, allPassed, details,
                totalDeductions, blackboxScore, whiteboxDeduction);
    }
}
