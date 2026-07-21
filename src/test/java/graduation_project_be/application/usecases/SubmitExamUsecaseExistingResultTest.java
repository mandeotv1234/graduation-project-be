package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.TeacherNotificationRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.GradingQueueService;
import graduation_project_be.application.port.services.HeartbeatService;
import graduation_project_be.application.usecases.request.SubmitExamRequest;
import graduation_project_be.application.usecases.response.SubmitExamResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.enums.GradingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmitExamUsecaseExistingResultTest {

    @Mock private ExamRepository examRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private ExamSubmissionRepository examSubmissionRepository;
    @Mock private ExamResultRepository examResultRepository;
    @Mock private ClassEnrollmentRepository classEnrollmentRepository;
    @Mock private ClassRepository classRepository;
    @Mock private UserRepository userRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private ExamSessionService examSessionService;
    @Mock private GradingQueueService gradingQueueService;
    @Mock private ExamDraftRepository examDraftRepository;
    @Mock private TeacherNotificationRepository teacherNotificationRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private HeartbeatService heartbeatService;

    @InjectMocks
    private SubmitExamUsecase usecase;

    @Test
    void invalidSessionReturnsPendingExistingResultWithoutFakeZeroScore() {
        ExamResult pendingResult = result(GradingStatus.PENDING, BigDecimal.ZERO);
        when(currentUserService.getCurrentUserId()).thenReturn(21L);
        when(examSessionService.isSessionValid(9L, 21L, "127.0.0.1", "browser"))
                .thenReturn(false);
        when(examResultRepository.findByExamIdAndStudentId(9L, 21L))
                .thenReturn(Optional.of(pendingResult));

        SubmitExamResponse response = usecase.execute(request());

        assertEquals(GradingStatus.PENDING, response.status());
        assertNull(response.totalScore());
        assertNull(response.maxScore());
        assertEquals(6, response.totalQuestions());
    }

    @Test
    void invalidSessionReturnsActualCompletedScore() {
        ExamResult completedResult = result(GradingStatus.COMPLETED, new BigDecimal("7.50"));
        completedResult.setCorrectCount(4);
        when(currentUserService.getCurrentUserId()).thenReturn(21L);
        when(examSessionService.isSessionValid(9L, 21L, "127.0.0.1", "browser"))
                .thenReturn(false);
        when(examResultRepository.findByExamIdAndStudentId(9L, 21L))
                .thenReturn(Optional.of(completedResult));
        when(examRepository.findById(9L)).thenReturn(Optional.of(exam(true)));

        SubmitExamResponse response = usecase.execute(request());

        assertEquals(GradingStatus.COMPLETED, response.status());
        assertEquals(new BigDecimal("7.50"), response.totalScore());
        assertEquals(4, response.correctCount());
    }

    @Test
    void invalidSessionHidesCompletedScoreWhenImmediateResultDisplayIsDisabled() {
        ExamResult completedResult = result(GradingStatus.COMPLETED, new BigDecimal("7.50"));
        when(currentUserService.getCurrentUserId()).thenReturn(21L);
        when(examSessionService.isSessionValid(9L, 21L, "127.0.0.1", "browser"))
                .thenReturn(false);
        when(examResultRepository.findByExamIdAndStudentId(9L, 21L))
                .thenReturn(Optional.of(completedResult));
        when(examRepository.findById(9L)).thenReturn(Optional.of(exam(false)));

        SubmitExamResponse response = usecase.execute(request());

        assertEquals(GradingStatus.COMPLETED, response.status());
        assertNull(response.totalScore());
        assertNull(response.maxScore());
        assertEquals(0, response.correctCount());
    }

    private SubmitExamRequest request() {
        return new SubmitExamRequest(9L, List.of(), "127.0.0.1", "browser");
    }

    private Exam exam(boolean showResultAfterSubmit) {
        return Exam.builder()
                .id(9L)
                .settings(ExamSettings.builder()
                        .showResultAfterSubmit(showResultAfterSubmit)
                        .build())
                .build();
    }

    private ExamResult result(GradingStatus status, BigDecimal totalScore) {
        return ExamResult.builder()
                .id(99L)
                .examId(9L)
                .studentId(21L)
                .attemptNumber(1)
                .totalScore(totalScore)
                .maxScore(BigDecimal.TEN)
                .correctCount(0)
                .totalQuestions(6)
                .submittedAt(LocalDateTime.of(2026, 7, 21, 20, 30))
                .status(status)
                .build();
    }
}
