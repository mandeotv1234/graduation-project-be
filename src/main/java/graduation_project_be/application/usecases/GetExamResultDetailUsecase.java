package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.usecases.response.GetExamResultDetailResponse;
import graduation_project_be.domain.models.*;
import graduation_project_be.domain.models.enums.GradingType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class GetExamResultDetailUsecase {

    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final UserRepository userRepository;
    private final TestCaseRepository testCaseRepository;
    private final ObjectMapper objectMapper;

    public GetExamResultDetailResponse execute(Long examId, Long resultId) {
        ExamResult result = examResultRepository.findById(resultId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamResult", "id", resultId));

        if (!result.getExamId().equals(examId)) {
            throw new IllegalArgumentException("Khác mã bài thi.");
        }

        User student = userRepository.findById(result.getStudentId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", result.getStudentId()));

        // Fetch all questions for content
        List<ExamQuestion> questions = examQuestionRepository.findByExamId(examId);
        // Fetch student's submissions for this specific attempt
        List<ExamSubmission> submissions = examSubmissionRepository.findByExamIdAndStudentIdAndAttemptNumber(
                examId, result.getStudentId(), result.getAttemptNumber());
        Map<Long, ExamSubmission> submissionMap = submissions.stream()
                .collect(Collectors.toMap(ExamSubmission::getQuestionId, s -> s));

        // Cache teacher name lookups — one teacher may override multiple questions
        Map<Long, String> teacherNameCache = new HashMap<>();

        List<GetExamResultDetailResponse.QuestionResultDetail> details = questions.stream()
                .map(q -> {
                    ExamSubmission submission = submissionMap.get(q.getId());

                    GradingType subGradingType = submission != null ? submission.getGradingType() : GradingType.AUTO;
                    Long gradedBy = submission != null ? submission.getGradedBy() : null;
                    String gradedByName = resolveTeacherName(gradedBy, teacherNameCache);

                    return new GetExamResultDetailResponse.QuestionResultDetail(
                            q.getId(),
                            submission != null ? submission.getId() : null,
                            q.getContent(),
                            submission != null ? submission.getStudentQuery() : "",
                            q.getCorrectQuery(),
                            submission != null && Boolean.TRUE.equals(submission.getIsCorrect()),
                            submission != null ? submission.getScoreEarned() : java.math.BigDecimal.ZERO,
                            q.getPoints(),
                            submission != null ? submission.getErrorMessage() : null,
                            submission != null ? submission.getExecutionTimeMs() : null,
                            q.getQuestionType() != null ? q.getQuestionType().name() : null,
                            subGradingType != null ? subGradingType.name() : GradingType.AUTO.name(),
                            gradedBy,
                            gradedByName,
                            submission != null ? submission.getGradedAt() : null,
                            submission != null ? submission.getTeacherComment() : null,
                            buildStoredProcedureTestCaseResults(q, submission),
                            parseGradingTrace(submission != null ? submission.getGradingTraceJson() : null)
                    );
                }).toList();

        String resultGradingType = result.getGradingType() != null
                ? result.getGradingType().name()
                : GradingType.AUTO.name();

        return new GetExamResultDetailResponse(
                result.getId(),
                result.getStudentId(),
                student.getFullName(),
                student.getEmail(),
                result.getAttemptNumber(),
                result.getTotalScore(),
                result.getMaxScore(),
                result.getCorrectCount(),
                result.getTotalQuestions(),
                result.getStatus(),
                result.getSubmittedAt(),
                resultGradingType,
                result.getLastGradedAt(),
                details
        );
    }

    private GradingTrace parseGradingTrace(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, GradingTrace.class);
        } catch (Exception e) {
            log.warn("Failed to parse grading trace: {}", e.getMessage());
            return null;
        }
    }

    private String resolveTeacherName(Long teacherId, Map<Long, String> cache) {
        if (teacherId == null) return null;
        return cache.computeIfAbsent(teacherId, id ->
                userRepository.findById(id)
                        .map(User::getFullName)
                        .orElse(null)
        );
    }

    private List<GetExamResultDetailResponse.TestCaseResultDetail> buildStoredProcedureTestCaseResults(
            ExamQuestion question,
            ExamSubmission submission) {
        if (question.getQuestionType() != QuestionType.STORED_PROCEDURE || submission == null) {
            return List.of();
        }

        List<TestCase> testCases = testCaseRepository.findByQuestionId(question.getId());
        if (testCases == null || testCases.isEmpty()) {
            return List.of();
        }

        List<TestCase> sortedCases = testCases.stream()
                .sorted(Comparator
                        .comparing((TestCase tc) -> tc.getOrderIndex() == null ? Integer.MAX_VALUE : tc.getOrderIndex())
                        .thenComparing(tc -> tc.getId() == null ? Long.MAX_VALUE : tc.getId()))
                .toList();

        String errorMessage = submission.getErrorMessage();
        Map<Long, String> failureMessages = extractCaseFailureMessages(errorMessage, sortedCases);
        boolean hasGlobalFailure = errorMessage != null
                && !errorMessage.isBlank()
                && failureMessages.isEmpty();

        BigDecimal questionPoints = question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO;
        List<GetExamResultDetailResponse.TestCaseResultDetail> results = new ArrayList<>();

        for (TestCase testCase : sortedCases) {
            BigDecimal weight = testCase.getScoreWeight() != null ? testCase.getScoreWeight() : BigDecimal.ZERO;
            BigDecimal maxPoints = questionPoints.multiply(weight);
            String failure = testCase.getId() != null ? failureMessages.get(testCase.getId()) : null;
            boolean passed = !hasGlobalFailure && failure == null;

            results.add(new GetExamResultDetailResponse.TestCaseResultDetail(
                    testCase.getId(),
                    testCase.getOrderIndex(),
                    resolveCaseName(testCase),
                    passed,
                    passed ? BigDecimal.ZERO : maxPoints,
                    maxPoints,
                    passed ? "Test case đúng, không bị trừ điểm"
                            : cleanFailureMessage(failure != null ? failure : errorMessage)
            ));
        }

        return results;
    }

    private Map<Long, String> extractCaseFailureMessages(String errorMessage, List<TestCase> testCases) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return Map.of();
        }

        List<FailureMarker> markers = new ArrayList<>();
        for (TestCase testCase : testCases) {
            String marker = "[" + resolveCaseName(testCase) + "]";
            int start = errorMessage.indexOf(marker);
            if (start >= 0 && testCase.getId() != null) {
                markers.add(new FailureMarker(testCase.getId(), start, start + marker.length()));
            }
        }
        if (markers.isEmpty()) {
            return Map.of();
        }

        markers.sort(Comparator.comparingInt(FailureMarker::start));
        Map<Long, String> result = new HashMap<>();
        for (int i = 0; i < markers.size(); i++) {
            FailureMarker marker = markers.get(i);
            int end = i + 1 < markers.size() ? markers.get(i + 1).start() : errorMessage.length();
            result.put(marker.testCaseId(), cleanFailureMessage(errorMessage.substring(marker.contentStart(), end)));
        }
        return result;
    }

    private String resolveCaseName(TestCase testCase) {
        if (testCase.getCaseName() != null && !testCase.getCaseName().isBlank()) {
            return testCase.getCaseName();
        }
        Integer orderIndex = testCase.getOrderIndex();
        return "TC" + (orderIndex != null ? orderIndex : "");
    }

    private String cleanFailureMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Test case không đạt";
        }
        String cleaned = message.trim();
        while (cleaned.startsWith(".") || cleaned.startsWith(":")) {
            cleaned = cleaned.substring(1).trim();
        }
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned.isBlank() ? "Test case không đạt" : cleaned;
    }

    private record FailureMarker(Long testCaseId, int start, int contentStart) {
    }
}
