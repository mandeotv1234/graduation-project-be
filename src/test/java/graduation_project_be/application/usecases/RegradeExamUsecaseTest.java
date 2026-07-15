package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.services.GradingQueueService;
import graduation_project_be.application.usecases.response.RegradeExamResponse;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import graduation_project_be.shared.utils.TimeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegradeExamUsecaseTest {

    private static final Long EXAM_ID = 19L;
    private static final Long RESULT_ID = 101L;
    private static final Long STUDENT_ID = 7L;
    private static final int ATTEMPT_NUMBER = 2;

    @Mock private ExamResultRepository examResultRepository;
    @Mock private ExamSubmissionRepository examSubmissionRepository;
    @Mock private GradingQueueService gradingQueueService;

    private RegradeExamUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new RegradeExamUsecase(
                examResultRepository,
                examSubmissionRepository,
                gradingQueueService);
    }

    @Test
    void execute_resetsResultAndSubmissionBeforeEnqueueingAfterCommit() {
        ExamResult result = ExamResult.builder()
                .id(RESULT_ID)
                .examId(EXAM_ID)
                .studentId(STUDENT_ID)
                .attemptNumber(ATTEMPT_NUMBER)
                .totalScore(new BigDecimal("7.50"))
                .correctCount(3)
                .status(GradingStatus.COMPLETED)
                .lastGradedAt(TimeUtils.now())
                .build();
        ExamSubmission submission = ExamSubmission.builder()
                .examId(EXAM_ID)
                .questionId(12L)
                .studentId(STUDENT_ID)
                .attemptNumber(ATTEMPT_NUMBER)
                .scoreEarned(new BigDecimal("2.50"))
                .isCorrect(true)
                .status(SubmissionStatus.GRADED)
                .gradingTraceJson("{\"old\":true}")
                .build();

        when(examResultRepository.findById(RESULT_ID)).thenReturn(Optional.of(result));
        when(examSubmissionRepository.findByExamIdAndStudentIdAndAttemptNumber(
                EXAM_ID, STUDENT_ID, ATTEMPT_NUMBER)).thenReturn(List.of(submission));

        TransactionSynchronizationManager.initSynchronization();
        RegradeExamResponse response;
        try {
            response = usecase.execute(EXAM_ID, RESULT_ID);

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(response.previousScores().totalScore()).isEqualByComparingTo("7.50");
        assertThat(response.previousScores().correctCount()).isEqualTo(3);
        assertThat(result.getStatus()).isEqualTo(GradingStatus.PENDING);
        assertThat(result.getTotalScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getCorrectCount()).isZero();
        assertThat(result.getLastGradedAt()).isNull();
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(submission.getScoreEarned()).isNull();
        assertThat(submission.getGradingTraceJson()).isNull();
        verify(gradingQueueService).enqueue(EXAM_ID, STUDENT_ID, ATTEMPT_NUMBER);
    }
}
