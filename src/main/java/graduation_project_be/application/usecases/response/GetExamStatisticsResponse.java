package graduation_project_be.application.usecases.response;

import java.util.List;

public record GetExamStatisticsResponse(
        // ===== KPI Cards =====
        int totalSubmissions,
        double averageScore,
        double maxScore,
        double minScore,
        double passRate,
        int suspiciousCount,

        // ===== Score Distribution =====
        List<ScoreDistributionBucket> scoreDistribution,

        // ===== Skill Analysis per QuestionType =====
        List<QuestionTypeAccuracy> questionTypeAccuracy,

        // ===== Behavior =====
        double avgCompletionTimeMinutes,
        List<SuspiciousStudent> suspiciousStudents
) {

    public record ScoreDistributionBucket(String range, int count) {}

    public record QuestionTypeAccuracy(
            String questionType,
            int totalAttempts,
            int correctCount,
            double accuracy
    ) {}

    public record SuspiciousStudent(
            Long studentId,
            String studentName,
            String studentEmail,
            int violationCount
    ) {}
}
