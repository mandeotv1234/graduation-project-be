package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.HeartbeatService;
import graduation_project_be.application.port.services.HeartbeatService.HeartbeatState;
import graduation_project_be.application.usecases.request.RecordHeartbeatRequest;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordHeartbeatUsecaseTest {

    private static final Long EXAM_ID = 1L;
    private static final Long STUDENT_ID = 2L;
    private static final Long CLASS_ID = 10L;

    @Mock private HeartbeatService heartbeatService;
    @Mock private ExamSessionService examSessionService;
    @Mock private ExamRepository examRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private ReportViolationUsecase reportViolationUsecase;
    @Captor private ArgumentCaptor<HeartbeatState> stateCaptor;

    private RecordHeartbeatUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new RecordHeartbeatUsecase(heartbeatService, examSessionService, examRepository,
                currentUserService, reportViolationUsecase);
    }

    private Exam examWith(Boolean integrityEnabled) {
        return Exam.builder()
                .id(EXAM_ID)
                .classId(CLASS_ID)
                .settings(ExamSettings.builder().integrityCheckEnabled(integrityEnabled).build())
                .build();
    }

    private void stubExam(Exam exam) {
        when(currentUserService.getCurrentUserId()).thenReturn(STUDENT_ID);
        when(examSessionService.getActiveSession(EXAM_ID, STUDENT_ID)).thenReturn(Optional.of("ip|ua"));
        when(examRepository.findByIdAndIsPublished(EXAM_ID, true)).thenReturn(Optional.of(exam));
    }

    private RecordHeartbeatRequest request(int seq, boolean integrityOk) {
        return new RecordHeartbeatRequest(EXAM_ID, seq, 0L, integrityOk, List.of());
    }

    private HeartbeatState savedState() {
        verify(heartbeatService).save(eq(EXAM_ID), eq(STUDENT_ID), stateCaptor.capture());
        return stateCaptor.getValue();
    }

    private void verifyNoViolation() {
        verify(reportViolationUsecase, never()).executeAsSystem(any(), any(), any(), any());
    }

    @Test
    void validHeartbeat_recordsStateAndRaisesNothing() {
        stubExam(examWith(true));
        when(heartbeatService.get(EXAM_ID, STUDENT_ID)).thenReturn(Optional.empty());

        usecase.execute(request(1, true));

        HeartbeatState state = savedState();
        assertThat(state.tamperStreak()).isZero();
        assertThat(state.flagged()).isFalse();
        verifyNoViolation();
    }

    @Test
    void heartbeatWithoutActiveSession_isClearedAndIgnored() {
        when(currentUserService.getCurrentUserId()).thenReturn(STUDENT_ID);
        when(examSessionService.getActiveSession(EXAM_ID, STUDENT_ID)).thenReturn(Optional.empty());

        usecase.execute(request(1, true));

        verify(heartbeatService).clear(EXAM_ID, STUDENT_ID);
        verify(heartbeatService, never()).save(any(), any(), any());
        verify(examRepository, never()).findByIdAndIsPublished(any(), any());
        verifyNoViolation();
    }

    @Test
    void integrityFalseOnce_incrementsStreakWithoutRaising() {
        stubExam(examWith(true));
        when(heartbeatService.get(EXAM_ID, STUDENT_ID)).thenReturn(Optional.empty());

        usecase.execute(request(1, false));

        assertThat(savedState().tamperStreak()).isEqualTo(1);
        verifyNoViolation();
    }

    @Test
    void integrityFalseTwice_raisesIntegrityTampered() {
        stubExam(examWith(true));
        when(heartbeatService.get(EXAM_ID, STUDENT_ID))
                .thenReturn(Optional.of(new HeartbeatState(0L, 1, 1, false)));

        usecase.execute(request(2, false));

        verify(reportViolationUsecase, times(1))
                .executeAsSystem(eq(EXAM_ID), eq(STUDENT_ID),
                        eq(RecordHeartbeatUsecase.INTEGRITY_TAMPERED), anyString());
        HeartbeatState state = savedState();
        assertThat(state.tamperStreak()).isZero();
        assertThat(state.flagged()).isTrue();
    }

    @Test
    void outOfOrderSeq_isAnomalyEvenWhenIntegrityOk() {
        stubExam(examWith(true));
        when(heartbeatService.get(EXAM_ID, STUDENT_ID))
                .thenReturn(Optional.of(new HeartbeatState(0L, 5, 0, false)));

        usecase.execute(request(3, true));

        assertThat(savedState().tamperStreak()).isEqualTo(1);
        verifyNoViolation();
    }

    @Test
    void normalHeartbeatAfterAnomaly_resetsStreakAndFlag() {
        stubExam(examWith(true));
        when(heartbeatService.get(EXAM_ID, STUDENT_ID))
                .thenReturn(Optional.of(new HeartbeatState(0L, 5, 1, true)));

        usecase.execute(request(6, true));

        HeartbeatState state = savedState();
        assertThat(state.tamperStreak()).isZero();
        assertThat(state.flagged()).isFalse();
        verifyNoViolation();
    }

    @Test
    void integrityDisabled_neverRaises() {
        stubExam(examWith(false));

        usecase.execute(request(1, false));

        assertThat(savedState().tamperStreak()).isZero();
        verifyNoViolation();
    }
}
