package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetExamStatisticsResponse;

import java.util.List;

public record GetExamStatisticsResponseDto(
        int totalSubmissions,
        double averageScore,
        double maxScore,
        double minScore,
        double passRate,
        int suspiciousCount,
        List<ScoreDistributionBucketDto> scoreDistribution,
        List<QuestionTypeAccuracyDto> questionTypeAccuracy,
        double avgCompletionTimeMinutes,
        List<SuspiciousStudentDto> suspiciousStudents
) {

    public record ScoreDistributionBucketDto(String range, int count) {}

    public record QuestionTypeAccuracyDto(
            String questionType,
            int totalAttempts,
            int correctCount,
            double accuracy
    ) {}

    public record SuspiciousStudentDto(
            Long studentId,
            String studentName,
            String studentEmail,
            int violationCount
    ) {}

    public static GetExamStatisticsResponseDto fromResponse(GetExamStatisticsResponse r) {
        List<ScoreDistributionBucketDto> dist = r.scoreDistribution().stream()
                .map(b -> new ScoreDistributionBucketDto(b.range(), b.count()))
                .toList();

        List<QuestionTypeAccuracyDto> accuracy = r.questionTypeAccuracy().stream()
                .map(a -> new QuestionTypeAccuracyDto(
                        a.questionType(), a.totalAttempts(), a.correctCount(), a.accuracy()))
                .toList();

        List<SuspiciousStudentDto> suspicious = r.suspiciousStudents().stream()
                .map(s -> new SuspiciousStudentDto(
                        s.studentId(), s.studentName(), s.studentEmail(), s.violationCount()))
                .toList();

        return new GetExamStatisticsResponseDto(
                r.totalSubmissions(),
                r.averageScore(),
                r.maxScore(),
                r.minScore(),
                r.passRate(),
                r.suspiciousCount(),
                dist,
                accuracy,
                r.avgCompletionTimeMinutes(),
                suspicious
        );
    }
}
