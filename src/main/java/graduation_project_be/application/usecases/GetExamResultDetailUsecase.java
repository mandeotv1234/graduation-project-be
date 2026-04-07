package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.usecases.response.GetExamResultDetailResponse;
import graduation_project_be.domain.models.*;
import graduation_project_be.domain.models.enums.GradingType;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetExamResultDetailUsecase {

    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final UserRepository userRepository;

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
                            submission != null ? submission.getTeacherComment() : null
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

    private String resolveTeacherName(Long teacherId, Map<Long, String> cache) {
        if (teacherId == null) return null;
        return cache.computeIfAbsent(teacherId, id ->
                userRepository.findById(id)
                        .map(User::getFullName)
                        .orElse(null)
        );
    }
}
