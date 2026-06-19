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
        Double whiteboxDeduction,
        Double finalScore) {

    public static RubricTestGradeResponse of(
            double earnedPoints,
            double totalPoints,
            boolean allPassed,
            List<Map<String, Object>> details) {
        return new RubricTestGradeResponse(
                earnedPoints, totalPoints, allPassed, details, null, null, null, null);
    }

    public static RubricTestGradeResponse of(
            double earnedPoints,
            double totalPoints,
            boolean allPassed,
            List<Map<String, Object>> details,
            Double totalDeductions) {
        return new RubricTestGradeResponse(
                earnedPoints, totalPoints, allPassed, details, totalDeductions, null, null, null);
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
                totalDeductions, blackboxScore, whiteboxDeduction, null);
    }

    public static RubricTestGradeResponse withWhitebox(
            double blackboxScore,
            double whiteboxDeduction,
            double finalScore,
            double totalPoints,
            boolean allPassed,
            List<Map<String, Object>> details,
            Double totalDeductions) {
        return new RubricTestGradeResponse(
                finalScore,
                totalPoints,
                allPassed,
                details,
                totalDeductions,
                blackboxScore,
                whiteboxDeduction,
                finalScore);
    }
}
