package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.services.GradingQueueService;
import graduation_project_be.application.usecases.response.RegradeAllExamResponse;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.GradingType;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class RegradeAllExamUsecase {

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final GradingQueueService gradingQueueService;

    @Transactional
    public RegradeAllExamResponse execute(Long examId) {
        // 1. Validate exam exists
        examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // 2. Find all COMPLETED results for this exam
        List<ExamResult> allResults = examResultRepository.findByExamId(examId);
        List<ExamResult> completedResults = allResults.stream()
                .filter(r -> r.getStatus() == GradingStatus.COMPLETED)
                .toList();

        if (completedResults.isEmpty()) {
            throw new BadRequestException("No completed submissions to re-grade for exam " + examId);
        }

        // 3. Collect jobs to enqueue after commit
        List<GradingQueueService.GradingJob> jobs = new ArrayList<>();
        int skippedCount = 0;

        for (ExamResult result : completedResults) {
            // Reset all submissions for this result
            List<ExamSubmission> submissions = examSubmissionRepository
                    .findByExamIdAndStudentIdAndAttemptNumber(examId, result.getStudentId(), result.getAttemptNumber());

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

            // Reset result
            result.setStatus(GradingStatus.PENDING);
            result.setGradingType(GradingType.AUTO);
            examResultRepository.save(result);

            jobs.add(new GradingQueueService.GradingJob(
                    examId, result.getStudentId(), result.getAttemptNumber(), 0));
        }

        // Count non-COMPLETED results that were skipped
        skippedCount = allResults.size() - completedResults.size();

        // 4. Enqueue all jobs AFTER DB commit
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (GradingQueueService.GradingJob job : jobs) {
                    gradingQueueService.enqueue(job.examId(), job.studentId(), job.attemptNumber());
                }
                log.info("Re-grade all: enqueued {} jobs for exam={}", jobs.size(), examId);
            }
        });

        return new RegradeAllExamResponse(
                completedResults.size(),
                skippedCount,
                "Re-grading " + completedResults.size() + " submissions"
        );
    }
}
