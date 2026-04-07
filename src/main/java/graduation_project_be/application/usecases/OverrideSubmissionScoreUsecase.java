package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.OverrideSubmissionScoreRequest;
import graduation_project_be.application.usecases.response.OverrideSubmissionScoreResponse;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.GradingType;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class OverrideSubmissionScoreUsecase {

    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public OverrideSubmissionScoreResponse execute(
            Long examId,
            Long resultId,
            Long submissionId,
            OverrideSubmissionScoreRequest request) {

        // 1. Load & validate ExamResult
        ExamResult result = examResultRepository.findById(resultId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamResult", "id", resultId));

        if (!result.getExamId().equals(examId)) {
            throw new BadRequestException("Result does not belong to exam " + examId);
        }
        if (result.getStatus() != GradingStatus.COMPLETED) {
            throw new BadRequestException("Cannot override score: exam result is not COMPLETED");
        }

        // 2. Load & validate ExamSubmission
        ExamSubmission submission = examSubmissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamSubmission", "id", submissionId));

        // Validate submission belongs to this result (same examId, studentId, attemptNumber)
        if (!submission.getExamId().equals(examId)
                || !submission.getStudentId().equals(result.getStudentId())
                || submission.getAttemptNumber() != result.getAttemptNumber()) {
            throw new BadRequestException("Submission does not belong to the specified result");
        }

        // 3. Load question for maxPoints validation
        ExamQuestion question = examQuestionRepository.findById(submission.getQuestionId())
                .orElseThrow(() -> new ResourceNotFoundException("ExamQuestion", "id", submission.getQuestionId()));

        if (request.scoreEarned() != null
                && question.getPoints() != null
                && request.scoreEarned().compareTo(question.getPoints()) > 0) {
            throw new BadRequestException(
                    "scoreEarned (" + request.scoreEarned() + ") exceeds question max points (" + question.getPoints() + ")");
        }

        // 4. Update submission fields
        submission.setScoreEarned(request.scoreEarned());
        submission.setIsCorrect(request.isCorrect());
        submission.setGradingType(GradingType.MANUAL);
        submission.setGradedBy(currentUserService.getCurrentUserId());
        submission.setGradedAt(LocalDateTime.now());
        submission.setTeacherComment(request.teacherComment());
        examSubmissionRepository.save(submission);

        // 5. Recalculate result totals from all submissions of this attempt
        List<ExamSubmission> allSubmissions = examSubmissionRepository
                .findByExamIdAndStudentIdAndAttemptNumber(examId, result.getStudentId(), result.getAttemptNumber());
        List<ExamQuestion> allQuestions = examQuestionRepository.findByExamId(examId);
        Map<Long, ExamQuestion> questionMap = allQuestions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, q -> q));

        // Single-pass aggregation: totalScore, correctCount, gradingType
        BigDecimal totalScore = BigDecimal.ZERO;
        int correctCount = 0;
        boolean hasAuto = false;
        boolean hasManual = false;
        for (ExamSubmission s : allSubmissions) {
            if (s.getScoreEarned() != null) {
                totalScore = totalScore.add(s.getScoreEarned());
            }
            if (Boolean.TRUE.equals(s.getIsCorrect())) {
                correctCount++;
            }
            if (s.getGradingType() == GradingType.AUTO) hasAuto = true;
            if (s.getGradingType() == GradingType.MANUAL) hasManual = true;
        }
        GradingType resultGradingType = (hasAuto && hasManual) ? GradingType.MIXED
                : hasManual ? GradingType.MANUAL : GradingType.AUTO;

        result.setTotalScore(totalScore);
        result.setCorrectCount(correctCount);
        result.setGradingType(resultGradingType);
        result.setLastGradedAt(LocalDateTime.now());
        examResultRepository.save(result);

        // 6. Build response
        return new OverrideSubmissionScoreResponse(
                submissionId,
                request.scoreEarned(),
                Boolean.TRUE.equals(request.isCorrect()),
                GradingType.MANUAL.name(),
                request.teacherComment(),
                new OverrideSubmissionScoreResponse.UpdatedResult(
                        totalScore,
                        correctCount,
                        resultGradingType.name()));
    }
}
