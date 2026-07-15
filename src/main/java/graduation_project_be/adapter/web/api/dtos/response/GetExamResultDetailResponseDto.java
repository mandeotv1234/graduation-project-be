package graduation_project_be.adapter.web.api.dtos.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import graduation_project_be.application.usecases.response.GetExamResultDetailResponse;
import graduation_project_be.domain.models.GradingTrace;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.domain.models.enums.GradingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record GetExamResultDetailResponseDto(
    Long resultId,
    Long submissionId,
    Long studentId,
    String studentName,
    String studentEmail,
    int attemptNumber,
    BigDecimal totalScore,
    BigDecimal maxScore,
    int correctCount,
    int totalQuestions,
    GradingStatus status,
    LocalDateTime submittedAt,
    String gradingType,
    LocalDateTime lastGradedAt,
    List<QuestionResultDetailDto> questionResults
) {
    public static GetExamResultDetailResponseDto fromResponse(GetExamResultDetailResponse r) {
        return new GetExamResultDetailResponseDto(
            r.resultId(),
            r.submissionId(),
            r.studentId(),
            r.studentName(),
            r.studentEmail(),
            r.attemptNumber(),
            r.totalScore(),
            r.maxScore(),
            r.correctCount(),
            r.totalQuestions(),
            r.status(),
            r.submittedAt(),
            r.gradingType(),
            r.lastGradedAt(),
            r.questionResults().stream()
                .map(QuestionResultDetailDto::fromResponse)
                .toList()
        );
    }

    public record QuestionResultDetailDto(
        Long questionId,
        Long submissionId,
        String content,
        String studentQuery,
        String correctQuery,
        boolean isCorrect,
        BigDecimal scoreEarned,
        BigDecimal maxPoints,
        String errorMessage,
        Integer executionTimeMs,
        String questionType,
        String gradingType,
        Long gradedBy,
        String gradedByName,
        LocalDateTime gradedAt,
        String teacherComment,
        List<TestCaseResultDetailDto> testCaseResults,
        @JsonInclude(JsonInclude.Include.NON_NULL) GradingTraceDto gradingTrace
    ) {
        public static QuestionResultDetailDto fromResponse(GetExamResultDetailResponse.QuestionResultDetail d) {
            return new QuestionResultDetailDto(
                d.questionId(), d.submissionId(), d.content(), d.studentQuery(), d.correctQuery(),
                d.isCorrect(), d.scoreEarned(), d.maxPoints(),
                d.errorMessage(), d.executionTimeMs(),
                d.questionType(), d.gradingType(),
                d.gradedBy(), d.gradedByName(), d.gradedAt(), d.teacherComment(),
                d.testCaseResults() == null
                    ? List.of()
                    : d.testCaseResults().stream()
                        .map(TestCaseResultDetailDto::fromResponse)
                        .toList(),
                GradingTraceDto.fromDomain(d.gradingTrace())
            );
        }
    }

    public record TestCaseResultDetailDto(
        Long testCaseId,
        Integer orderIndex,
        String caseName,
        boolean passed,
        BigDecimal scoreEarned,
        BigDecimal maxPoints,
        String message
    ) {
        public static TestCaseResultDetailDto fromResponse(GetExamResultDetailResponse.TestCaseResultDetail d) {
            return new TestCaseResultDetailDto(
                d.testCaseId(),
                d.orderIndex(),
                d.caseName(),
                d.passed(),
                d.scoreEarned(),
                d.maxPoints(),
                d.message()
            );
        }
    }

    public record GradingTraceDto(
        int traceSchemaVersion,
        String gradingRunVersion,
        LocalDateTime generatedAt,
        int attemptNumber,
        String rubricHash,
        List<GradingTraceItemDto> items
    ) {
        public static GradingTraceDto fromDomain(GradingTrace trace) {
            if (trace == null) return null;
            return new GradingTraceDto(
                trace.traceSchemaVersion(),
                trace.gradingRunVersion(),
                trace.generatedAt(),
                trace.attemptNumber(),
                trace.rubricHash(),
                trace.items() == null
                    ? List.of()
                    : trace.items().stream()
                        .map(GradingTraceItemDto::fromDomain)
                        .toList()
            );
        }
    }

    public record GradingTraceItemDto(
        String kind,
        String status,
        String label,
        String message,
        String caseId,
        String caseName,
        String ruleTarget,
        String ruleCondition,
        String action,
        BigDecimal configuredPenalty,
        BigDecimal earnedPoints,
        BigDecimal maxPoints,
        BigDecimal deductedPoints,
        String expected,
        String actual,
        String configSummary
    ) {
        public static GradingTraceItemDto fromDomain(GradingTraceItem item) {
            if (item == null) return null;
            return new GradingTraceItemDto(
                item.kind(),
                item.status(),
                item.label(),
                item.message(),
                item.caseId(),
                item.caseName(),
                item.ruleTarget(),
                item.ruleCondition(),
                item.action(),
                item.configuredPenalty(),
                item.earnedPoints(),
                item.maxPoints(),
                item.deductedPoints(),
                item.expected(),
                item.actual(),
                item.configSummary()
            );
        }
    }
}
