package graduation_project_be.application.usecases.response;

import java.util.List;

public record GetExamMutationAnalyticsResponse(
        Long examId,
        int totalStudents,
        List<QuestionMutationSummary> questionSummaries,
        GlobalInsights globalInsights
) {

    public record QuestionMutationSummary(
            Long questionId,
            int orderIndex,
            String questionTitle,
            double avgScore,
            double maxScore,
            double passRate,
            List<MutationStat> mutationBreakdown,
            List<String> rubricHealthWarnings
    ) {}

    public record MutationStat(
            String mutationType,
            String label,
            int failCount,
            double failRate,
            double avgDeduction
    ) {}

    public record GlobalInsights(
            List<String> topMutationTypes,
            List<String> studyRecommendations
    ) {}
}
