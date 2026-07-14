package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
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
import graduation_project_be.domain.models.enums.RegradeAllScope;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegradeAllExamUsecaseTest {

    private static final Long EXAM_ID = 19L;

    @Mock private ExamRepository examRepository;
    @Mock private ExamResultRepository examResultRepository;
    @Mock private ExamSubmissionRepository examSubmissionRepository;
    @Mock private GradingQueueService gradingQueueService;

    private RegradeAllExamUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new RegradeAllExamUsecase(
                examRepository,
                examResultRepository,
                examSubmissionRepository,
                gradingQueueService);
    }

    @Test
    void execute_latestAttemptScope_regradesOnlyLatestAttemptPerStudent() {
        when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam(3)));
        when(examResultRepository.findByExamId(EXAM_ID)).thenReturn(List.of(
                result(1L, 1, GradingStatus.COMPLETED),
                result(1L, 2, GradingStatus.COMPLETED),
                result(2L, 1, GradingStatus.FAILED),
                result(2L, 2, GradingStatus.GRADING)));
        when(examSubmissionRepository.findByExamIdAndStudentIdAndAttemptNumber(EXAM_ID, 1L, 2))
                .thenReturn(List.of(submission(1L, 2)));

        TransactionSynchronizationManager.initSynchronization();
        try {
            RegradeAllExamResponse response = usecase.execute(new RegradeAllExamRequest(
                    EXAM_ID,
                    RegradeAllScope.LATEST_ATTEMPT,
                    null));

            assertThat(response.queuedCount()).isEqualTo(1);
            assertThat(response.skippedCount()).isEqualTo(1);

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(examSubmissionRepository).findByExamIdAndStudentIdAndAttemptNumber(EXAM_ID, 1L, 2);
        verify(examSubmissionRepository, never()).findByExamIdAndStudentIdAndAttemptNumber(EXAM_ID, 1L, 1);
        verify(examSubmissionRepository, never()).findByExamIdAndStudentIdAndAttemptNumber(EXAM_ID, 2L, 1);
        verify(examSubmissionRepository, never()).findByExamIdAndStudentIdAndAttemptNumber(EXAM_ID, 2L, 2);
        verify(gradingQueueService).enqueue(EXAM_ID, 1L, 2);
    }

    @Test
    void execute_singleAttemptExam_rejectsNonAllScope() {
        when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam(1)));

        assertThatThrownBy(() -> usecase.execute(new RegradeAllExamRequest(
                EXAM_ID,
                RegradeAllScope.FIRST_ATTEMPT,
                null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Single-attempt exams");
    }

    private Exam exam(int maxAttempts) {
        return Exam.builder()
                .id(EXAM_ID)
                .maxAttempts(maxAttempts)
                .build();
    }

    private ExamResult result(Long studentId, int attemptNumber, GradingStatus status) {
        return ExamResult.builder()
                .examId(EXAM_ID)
                .studentId(studentId)
                .attemptNumber(attemptNumber)
                .status(status)
                .build();
    }

    private ExamSubmission submission(Long studentId, int attemptNumber) {
        return ExamSubmission.builder()
                .examId(EXAM_ID)
                .studentId(studentId)
                .attemptNumber(attemptNumber)
                .status(SubmissionStatus.GRADED)
                .build();
    }
}
