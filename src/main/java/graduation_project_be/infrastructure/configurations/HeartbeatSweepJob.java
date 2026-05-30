package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.HeartbeatService;
import graduation_project_be.application.port.services.HeartbeatService.HeartbeatKey;
import graduation_project_be.application.port.services.HeartbeatService.HeartbeatState;
import graduation_project_be.application.usecases.RecordHeartbeatUsecase;
import graduation_project_be.application.usecases.ReportViolationUsecase;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects the main anti-tamper threat: heartbeats that STOP entirely (anti-cheat scripts
 * killed → no more POSTs). Periodically scans tracked heartbeats and raises INTEGRITY_TAMPERED
 * for students whose last heartbeat is older than the per-exam gap, after a 2-cycle grace
 * that absorbs transient network drops. Scheduling is enabled globally by AutoSubmitWorkerConfiguration.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class HeartbeatSweepJob {

    private final HeartbeatService heartbeatService;
    private final ExamRepository examRepository;
    private final ReportViolationUsecase reportViolationUsecase;

    @Scheduled(fixedDelay = 10000)
    public void sweep() {
        try {
            List<HeartbeatKey> keys = heartbeatService.scanActive();
            if (keys.isEmpty()) return;

            long now = System.currentTimeMillis();
            Map<Long, Exam> examCache = new HashMap<>();

            for (HeartbeatKey key : keys) {
                Optional<HeartbeatState> stateOpt = heartbeatService.get(key.examId(), key.studentId());
                if (stateOpt.isEmpty()) continue;
                HeartbeatState state = stateOpt.get();

                Exam exam = examCache.computeIfAbsent(key.examId(),
                        id -> examRepository.findByIdAndIsPublished(id, true).orElse(null));
                if (exam == null || !integrityEnabled(exam)) continue;

                long ageSec = (now - state.lastSeenEpochMs()) / 1000;
                if (ageSec <= resolveGapSec(exam)) continue;

                int newStreak = state.tamperStreak() + 1;
                if (newStreak >= RecordHeartbeatUsecase.TAMPER_STREAK_THRESHOLD && !state.flagged()) {
                    raiseAbsence(key, ageSec);
                    heartbeatService.save(key.examId(), key.studentId(),
                            new HeartbeatState(state.lastSeenEpochMs(), state.lastSeq(), 0, true));
                } else {
                    heartbeatService.save(key.examId(), key.studentId(),
                            new HeartbeatState(state.lastSeenEpochMs(), state.lastSeq(), newStreak, state.flagged()));
                }
            }
        } catch (Exception e) {
            log.error("Heartbeat absence sweep failed", e);
        }
    }

    private void raiseAbsence(HeartbeatKey key, long ageSec) {
        try {
            reportViolationUsecase.executeAsSystem(
                    key.examId(), key.studentId(), RecordHeartbeatUsecase.INTEGRITY_TAMPERED,
                    "No heartbeat for " + ageSec + "s — anti-cheat monitoring may have been disabled.");
            log.warn("INTEGRITY_TAMPERED raised (heartbeat absence): exam={}, student={}, ageSec={}",
                    key.examId(), key.studentId(), ageSec);
        } catch (Exception e) {
            log.error("Failed to raise heartbeat-absence violation: exam={}, student={}: {}",
                    key.examId(), key.studentId(), e.getMessage());
        }
    }

    private boolean integrityEnabled(Exam exam) {
        ExamSettings settings = exam.getSettings();
        return settings == null || settings.getIntegrityCheckEnabled() == null
                || Boolean.TRUE.equals(settings.getIntegrityCheckEnabled());
    }

    private int resolveGapSec(Exam exam) {
        ExamSettings settings = exam.getSettings();
        if (settings == null || settings.getMaxHeartbeatGapSec() == null) {
            return RecordHeartbeatUsecase.DEFAULT_MAX_GAP_SEC;
        }
        return settings.getMaxHeartbeatGapSec();
    }
}
