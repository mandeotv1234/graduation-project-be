package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.services.GradingQueueService;
import graduation_project_be.application.usecases.response.RegradeExamResponse;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.GradingType;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class RegradeExamUsecase {

    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final GradingQueueService gradingQueueService;

    @Transactional
    public RegradeExamResponse execute(Long examId, Long resultId) {
        // 1. Load & validate
        ExamResult result = examResultRepository.findById(resultId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamResult", "id", resultId));

        if (!result.getExamId().equals(examId)) {
            throw new BadRequestException("Result does not belong to exam " + examId);
        }
        if (result.getStatus() != GradingStatus.COMPLETED) {
            throw new BadRequestException("Cannot re-grade: result status is " + result.getStatus());
        }

        // 2. Snapshot current scores before reset
        List<ExamSubmission> submissions = examSubmissionRepository
                .findByExamIdAndStudentIdAndAttemptNumber(examId, result.getStudentId(), result.getAttemptNumber());

        List<RegradeExamResponse.QuestionSnapshot> questionSnapshots = submissions.stream()
                .map(s -> new RegradeExamResponse.QuestionSnapshot(
                        s.getQuestionId(),
                        s.getScoreEarned() != null ? s.getScoreEarned() : BigDecimal.ZERO,
                        Boolean.TRUE.equals(s.getIsCorrect())))
                .toList();

        RegradeExamResponse.PreviousScores previousScores = new RegradeExamResponse.PreviousScores(
                result.getTotalScore(), result.getCorrectCount(), questionSnapshots);

        // 3. Reset submissions to PENDING for re-grading (batch save)
        for (ExamSubmission submission : submissions) {
            submission.setStatus(SubmissionStatus.PENDING);
            submission.setScoreEarned(null);
            submission.setIsCorrect(null);
            submission.setErrorMessage(null);
            submission.setExecutionTimeMs(null);
            submission.setGradingType(GradingType.AUTO);
            submission.setGradedBy(null);
            submission.setGradedAt(null);
            submission.setTeacherComment(null);
        }
        examSubmissionRepository.saveAll(submissions);

        // 4. Reset result to PENDING
        result.setStatus(GradingStatus.PENDING);
        result.setGradingType(GradingType.AUTO);
        examResultRepository.save(result);

        // 5. Enqueue grading job — the existing GradeExamUsecase worker picks this up
        gradingQueueService.enqueue(examId, result.getStudentId(), result.getAttemptNumber());
        log.info("Re-grade enqueued for exam={}, student={}, attempt={}",
                examId, result.getStudentId(), result.getAttemptNumber());

        return new RegradeExamResponse("Re-grading started", previousScores);
    }
}
