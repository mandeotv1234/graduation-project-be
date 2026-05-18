package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GetMyResultDetailRequest;
import graduation_project_be.application.usecases.response.GetExamResultDetailResponse;
import graduation_project_be.domain.models.*;
import graduation_project_be.domain.models.enums.GradingType;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetMyResultDetailUsecase {

    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public GetExamResultDetailResponse execute(GetMyResultDetailRequest request) {
        Long resultId = request.resultId();
        Long currentStudentId = currentUserService.getCurrentUserId();

        ExamResult result = examResultRepository.findById(resultId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamResult", "id", resultId));

        if (!result.getStudentId().equals(currentStudentId)) {
            throw new UnauthorizedException("Bạn không có quyền xem kết quả này.");
        }

        Exam exam = examRepository.findById(result.getExamId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", result.getExamId()));

        if (exam.getSettings() == null || !Boolean.TRUE.equals(exam.getSettings().getAllowReview())) {
            throw new UnauthorizedException("Giáo viên không cho phép xem lại bài làm này.");
        }

        User student = userRepository.findById(result.getStudentId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", result.getStudentId()));

        List<ExamQuestion> questions = examQuestionRepository.findByExamId(exam.getId());
        List<ExamSubmission> submissions = examSubmissionRepository.findByExamIdAndStudentIdAndAttemptNumber(
                exam.getId(), result.getStudentId(), result.getAttemptNumber());
        Map<Long, ExamSubmission> submissionMap = submissions.stream()
                .collect(Collectors.toMap(ExamSubmission::getQuestionId, s -> s));

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
                            List.of()
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
                result.getGradingType() != null ? result.getGradingType().name() : GradingType.AUTO.name(),
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
