package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.HeartbeatService;
import graduation_project_be.application.port.services.HeartbeatService.HeartbeatKey;
import graduation_project_be.application.port.services.HeartbeatService.HeartbeatState;
import graduation_project_be.application.usecases.RecordHeartbeatUsecase;
import graduation_project_be.application.usecases.ReportViolationUsecase;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HeartbeatSweepJobTest {

    private static final Long EXAM_ID = 1L;
    private static final Long STUDENT_ID = 2L;

    @Mock private HeartbeatService heartbeatService;
    @Mock private ExamSessionService examSessionService;
    @Mock private ExamRepository examRepository;
    @Mock private ReportViolationUsecase reportViolationUsecase;
    @Captor private ArgumentCaptor<HeartbeatState> stateCaptor;

    private HeartbeatSweepJob job;

    @BeforeEach
    void setUp() {
        job = new HeartbeatSweepJob(heartbeatService, examSessionService, examRepository, reportViolationUsecase);
        ReflectionTestUtils.setField(job, "heartbeatSweepEnabled", true);
    }

    private void stub(HeartbeatState state, Boolean integrityEnabled) {
        when(heartbeatService.scanActive()).thenReturn(List.of(new HeartbeatKey(EXAM_ID, STUDENT_ID)));
        when(examSessionService.getActiveSession(EXAM_ID, STUDENT_ID)).thenReturn(Optional.of("ip|ua"));
        when(heartbeatService.get(EXAM_ID, STUDENT_ID)).thenReturn(Optional.of(state));
        Exam exam = Exam.builder()
                .id(EXAM_ID)
                .settings(ExamSettings.builder().integrityCheckEnabled(integrityEnabled).build())
                .build();
        when(examRepository.findByIdAndIsPublished(EXAM_ID, true)).thenReturn(Optional.of(exam));
    }

    private long secondsAgo(long sec) {
        return System.currentTimeMillis() - sec * 1000;
    }

    @Test
    void withinGap_noActionNoSave() {
        stub(new HeartbeatState(secondsAgo(5), 3, 0, false), true);

        job.sweep();

        verify(heartbeatService, never()).save(any(), any(), any());
        verify(reportViolationUsecase, never()).executeAsSystem(any(), any(), any(), any());
    }

    @Test
    void heartbeatWithoutActiveSession_isClearedWithoutViolation() {
        when(heartbeatService.scanActive()).thenReturn(List.of(new HeartbeatKey(EXAM_ID, STUDENT_ID)));
        when(examSessionService.getActiveSession(EXAM_ID, STUDENT_ID)).thenReturn(Optional.empty());

        job.sweep();

        verify(heartbeatService).clear(EXAM_ID, STUDENT_ID);
        verify(heartbeatService, never()).save(any(), any(), any());
        verify(examRepository, never()).findByIdAndIsPublished(any(), any());
        verify(reportViolationUsecase, never()).executeAsSystem(any(), any(), any(), any());
    }

    @Test
    void beyondGapFirstCycle_incrementsStreakWithoutRaising() {
        stub(new HeartbeatState(secondsAgo(60), 3, 0, false), true);

        job.sweep();

        verify(heartbeatService).save(eq(EXAM_ID), eq(STUDENT_ID), stateCaptor.capture());
        assertThat(stateCaptor.getValue().tamperStreak()).isEqualTo(1);
        assertThat(stateCaptor.getValue().flagged()).isFalse();
        verify(reportViolationUsecase, never()).executeAsSystem(any(), any(), any(), any());
    }

    @Test
    void beyondGapSecondCycle_raisesAndClearsKey() {
        stub(new HeartbeatState(secondsAgo(60), 3, 1, false), true);

        job.sweep();

        verify(reportViolationUsecase).executeAsSystem(eq(EXAM_ID), eq(STUDENT_ID),
                eq(RecordHeartbeatUsecase.INTEGRITY_TAMPERED), anyString());
        // On raise we clear the key (no resurrection / no re-scan), not persist a flagged state.
        verify(heartbeatService).clear(EXAM_ID, STUDENT_ID);
        verify(heartbeatService, never()).save(any(), any(), any());
    }

    @Test
    void alreadyFlagged_doesNotRaiseAgain() {
        stub(new HeartbeatState(secondsAgo(60), 3, 5, true), true);

        job.sweep();

        verify(reportViolationUsecase, never()).executeAsSystem(any(), any(), any(), any());
        verify(heartbeatService).save(eq(EXAM_ID), eq(STUDENT_ID), stateCaptor.capture());
        assertThat(stateCaptor.getValue().flagged()).isTrue();
    }

    @Test
    void integrityDisabled_skipsEntirely() {
        stub(new HeartbeatState(secondsAgo(60), 3, 1, false), false);

        job.sweep();

        verify(heartbeatService, never()).save(any(), any(), any());
        verify(reportViolationUsecase, never()).executeAsSystem(any(), any(), any(), any());
    }
}
