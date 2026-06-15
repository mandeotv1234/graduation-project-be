package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetExamMutationAnalyticsResponse;

import java.util.List;

public record GetExamMutationAnalyticsResponseDto(
        Long examId,
        int totalStudents,
        List<QuestionMutationSummaryDto> questionSummaries,
        GlobalInsightsDto globalInsights
) {

    public record QuestionMutationSummaryDto(
            Long questionId,
            int orderIndex,
            String questionTitle,
            double avgScore,
            double maxScore,
            double passRate,
            List<MutationStatDto> mutationBreakdown,
            List<String> rubricHealthWarnings
    ) {}

    public record MutationStatDto(
            String mutationType,
            String label,
            int failCount,
            double failRate,
            double avgDeduction
    ) {}

    public record GlobalInsightsDto(
            List<String> topMutationTypes,
            List<String> studyRecommendations
    ) {}

    public static GetExamMutationAnalyticsResponseDto fromResponse(GetExamMutationAnalyticsResponse r) {
        List<QuestionMutationSummaryDto> summaries = r.questionSummaries().stream()
                .map(q -> new QuestionMutationSummaryDto(
                        q.questionId(),
                        q.orderIndex(),
                        q.questionTitle(),
                        q.avgScore(),
                        q.maxScore(),
                        q.passRate(),
                        q.mutationBreakdown().stream()
                                .map(m -> new MutationStatDto(
                                        m.mutationType(), m.label(), m.failCount(), m.failRate(), m.avgDeduction()))
                                .toList(),
                        q.rubricHealthWarnings()))
                .toList();

        GlobalInsightsDto insights = new GlobalInsightsDto(
                r.globalInsights().topMutationTypes(),
                r.globalInsights().studyRecommendations());

        return new GetExamMutationAnalyticsResponseDto(
                r.examId(), r.totalStudents(), summaries, insights);
    }
}
