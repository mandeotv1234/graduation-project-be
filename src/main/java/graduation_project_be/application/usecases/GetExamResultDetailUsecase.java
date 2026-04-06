package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.usecases.response.GetExamResultDetailResponse;
import graduation_project_be.domain.models.*;
import lombok.RequiredArgsConstructor;

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

        List<GetExamResultDetailResponse.QuestionResultDetail> details = questions.stream()
                .map(q -> {
                    ExamSubmission submission = submissionMap.get(q.getId());
                    return new GetExamResultDetailResponse.QuestionResultDetail(
                            q.getId(),
                            q.getContent(),
                            submission != null ? submission.getStudentQuery() : "",
                            q.getCorrectQuery(),
                            submission != null && Boolean.TRUE.equals(submission.getIsCorrect()),
                            submission != null ? submission.getScoreEarned() : java.math.BigDecimal.ZERO,
                            q.getPoints(),
                            submission != null ? submission.getErrorMessage() : null,
                            submission != null ? submission.getExecutionTimeMs() : null
                    );
                }).toList();

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
                details
        );
    }
}
