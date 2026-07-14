package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.services.GradingQueueService;
import graduation_project_be.application.usecases.request.RegradeAllExamRequest;
import graduation_project_be.application.usecases.response.RegradeAllExamResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.GradingType;
import graduation_project_be.domain.models.enums.RegradeAllScope;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class RegradeAllExamUsecase {

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final GradingQueueService gradingQueueService;

    @Transactional
    public RegradeAllExamResponse execute(Long examId) {
        return execute(new RegradeAllExamRequest(examId, RegradeAllScope.ALL_ATTEMPTS, null));
    }

    @Transactional
    public RegradeAllExamResponse execute(RegradeAllExamRequest request) {
        Long examId = request.examId();
        RegradeAllScope scope = request.scope() != null ? request.scope() : RegradeAllScope.ALL_ATTEMPTS;

        // 1. Validate exam exists and requested attempt scope is allowed
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));
        validateScope(exam, scope, request.attemptNumber());

        // 2. Apply attempt scope first, then keep only regradable terminal results
        List<ExamResult> allResults = examResultRepository.findByExamId(examId);
        List<ExamResult> scopedResults = filterByScope(allResults, scope, request.attemptNumber());
        if (scopedResults.isEmpty()) {
            throw new BadRequestException("No submissions match selected re-grade scope for exam " + examId);
        }

        List<ExamResult> regradableResults = scopedResults.stream()
                .filter(r -> isRegradableStatus(r.getStatus()))
                .toList();

        if (regradableResults.isEmpty()) {
            throw new BadRequestException(
                    "No completed, failed, or system-error submissions to re-grade for selected scope");
        }

        // 3. Collect jobs to enqueue after commit
        List<GradingQueueService.GradingJob> jobs = new ArrayList<>();
        int skippedCount = 0;

        for (ExamResult result : regradableResults) {
            // Reset all submissions for this result
            List<ExamSubmission> submissions = examSubmissionRepository
                    .findByExamIdAndStudentIdAndAttemptNumber(examId, result.getStudentId(), result.getAttemptNumber());

            for (ExamSubmission submission : submissions) {
                submission.setStatus(SubmissionStatus.PENDING);
                submission.setScoreEarned(null);
                submission.setIsCorrect(null);
                submission.setErrorMessage(null);
                submission.setExecutionTimeMs(null);
                submission.setGradingTraceJson(null);
                submission.setGradingType(GradingType.AUTO);
                submission.setGradedBy(null);
                submission.setGradedAt(null);
                submission.setTeacherComment(null);
            }
            examSubmissionRepository.saveAll(submissions);

            // Reset result
            result.setStatus(GradingStatus.PENDING);
            result.setTotalScore(BigDecimal.ZERO);
            result.setCorrectCount(0);
            result.setGradingType(GradingType.AUTO);
            result.setLastGradedAt(null);
            examResultRepository.save(result);

            jobs.add(new GradingQueueService.GradingJob(
                    examId, result.getStudentId(), result.getAttemptNumber(), 0));
        }

        skippedCount = scopedResults.size() - regradableResults.size();

        // 4. Enqueue all jobs AFTER DB commit
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (GradingQueueService.GradingJob job : jobs) {
                    gradingQueueService.enqueue(job.examId(), job.studentId(), job.attemptNumber());
                }
                log.info("Re-grade all: enqueued {} jobs for exam={}, scope={}, attempt={}",
                        jobs.size(), examId, scope, request.attemptNumber());
            }
        });

        return new RegradeAllExamResponse(
                regradableResults.size(),
                skippedCount,
                buildMessage(regradableResults.size(), scope, request.attemptNumber())
        );
    }

    private void validateScope(Exam exam, RegradeAllScope scope, Integer attemptNumber) {
        Integer maxAttempts = exam.getMaxAttempts();
        boolean singleAttemptExam = maxAttempts != null && maxAttempts == 1;

        if (singleAttemptExam && scope != RegradeAllScope.ALL_ATTEMPTS) {
            throw new BadRequestException("Single-attempt exams only support re-grading all submissions");
        }

        if (scope != RegradeAllScope.SPECIFIC_ATTEMPT) {
            return;
        }

        if (attemptNumber == null || attemptNumber < 1) {
            throw new BadRequestException("Attempt number is required for specific-attempt re-grade");
        }

        if (maxAttempts != null && maxAttempts > 0 && attemptNumber > maxAttempts) {
            throw new BadRequestException("Attempt number exceeds exam max attempts");
        }
    }

    private List<ExamResult> filterByScope(
            List<ExamResult> allResults,
            RegradeAllScope scope,
            Integer attemptNumber) {
        return switch (scope) {
            case ALL_ATTEMPTS -> allResults;
            case FIRST_ATTEMPT -> allResults.stream()
                    .filter(result -> result.getAttemptNumber() == 1)
                    .toList();
            case LATEST_ATTEMPT -> latestAttemptByStudent(allResults);
            case SPECIFIC_ATTEMPT -> allResults.stream()
                    .filter(result -> result.getAttemptNumber() == attemptNumber)
                    .toList();
        };
    }

    private List<ExamResult> latestAttemptByStudent(List<ExamResult> allResults) {
        Map<Long, ExamResult> latestByStudent = new LinkedHashMap<>();
        for (ExamResult result : allResults) {
            ExamResult existing = latestByStudent.get(result.getStudentId());
            if (existing == null || result.getAttemptNumber() > existing.getAttemptNumber()) {
                latestByStudent.put(result.getStudentId(), result);
            }
        }
        return new ArrayList<>(latestByStudent.values());
    }

    private String buildMessage(int queuedCount, RegradeAllScope scope, Integer attemptNumber) {
        return switch (scope) {
            case ALL_ATTEMPTS -> "Re-grading " + queuedCount + " submissions";
            case FIRST_ATTEMPT -> "Re-grading " + queuedCount + " first-attempt submissions";
            case LATEST_ATTEMPT -> "Re-grading " + queuedCount + " latest-attempt submissions";
            case SPECIFIC_ATTEMPT -> "Re-grading " + queuedCount + " submissions for attempt " + attemptNumber;
        };
    }

    private boolean isRegradableStatus(GradingStatus status) {
        return status == GradingStatus.COMPLETED
                || status == GradingStatus.FAILED
                || status == GradingStatus.SYSTEM_ERROR;
    }
}
