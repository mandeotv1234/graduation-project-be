package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.HeartbeatService;
import graduation_project_be.application.port.services.HeartbeatService.HeartbeatState;
import graduation_project_be.application.usecases.request.RecordHeartbeatRequest;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Records a student's exam heartbeat and detects tampering on arrival. The real
 * threat (anti-cheat scripts killed → heartbeats stop entirely) is caught by the
 * scheduled absence sweep; this handles the signals that DO arrive: a client-reported
 * integrity failure, or a replayed/out-of-order sequence number.
 */
@Slf4j
@RequiredArgsConstructor
public class RecordHeartbeatUsecase {

    public static final String INTEGRITY_TAMPERED = "INTEGRITY_TAMPERED";
    public static final int DEFAULT_MAX_GAP_SEC = 25;
    public static final int TAMPER_STREAK_THRESHOLD = 2;
    private static final boolean DEFAULT_INTEGRITY_ENABLED = true;

    private final HeartbeatService heartbeatService;
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;
    private final ReportViolationUsecase reportViolationUsecase;

    public void execute(RecordHeartbeatRequest request) {
        Long studentId = currentUserService.getCurrentUserId();
        Long examId = request.examId();

        Exam exam = examRepository.findByIdAndIsPublished(examId, true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        long now = System.currentTimeMillis();
        Optional<HeartbeatState> priorOpt = heartbeatService.get(examId, studentId);

        // Integrity detection disabled for this exam → only track liveness for the monitor.
        if (!integrityEnabled(exam)) {
            heartbeatService.save(examId, studentId, new HeartbeatState(now, request.seq(), 0, false));
            return;
        }

        int priorSeq = priorOpt.map(HeartbeatState::lastSeq).orElse(-1);
        int priorStreak = priorOpt.map(HeartbeatState::tamperStreak).orElse(0);

        // Arrival anomaly: client reported a failed integrity check, OR a replayed/out-of-order seq.
        boolean anomaly = !request.integrityOk()
                || (priorOpt.isPresent() && request.seq() <= priorSeq);

        if (!anomaly) {
            // Clean heartbeat → reset streak + flagged so a later tamper episode can re-raise.
            heartbeatService.save(examId, studentId, new HeartbeatState(now, request.seq(), 0, false));
            return;
        }

        int newStreak = priorStreak + 1;
        boolean alreadyFlagged = priorOpt.map(HeartbeatState::flagged).orElse(false);

        if (newStreak >= TAMPER_STREAK_THRESHOLD && !alreadyFlagged) {
            raiseTamper(examId, studentId, request.failedChecks());
            heartbeatService.save(examId, studentId, new HeartbeatState(now, request.seq(), 0, true));
        } else {
            heartbeatService.save(examId, studentId,
                    new HeartbeatState(now, request.seq(), newStreak, alreadyFlagged));
        }
    }

    private void raiseTamper(Long examId, Long studentId, List<String> failedChecks) {
        String description = "Client integrity check failed"
                + (failedChecks != null && !failedChecks.isEmpty() ? ": " + String.join(", ", failedChecks) : "");
        try {
            reportViolationUsecase.executeAsSystem(examId, studentId, INTEGRITY_TAMPERED, description);
        } catch (Exception e) {
            log.error("Failed to raise INTEGRITY_TAMPERED (arrival) exam={}, student={}: {}",
                    examId, studentId, e.getMessage());
        }
    }

    private boolean integrityEnabled(Exam exam) {
        ExamSettings settings = exam.getSettings();
        if (settings == null || settings.getIntegrityCheckEnabled() == null) {
            return DEFAULT_INTEGRITY_ENABLED;
        }
        return settings.getIntegrityCheckEnabled();
    }
}
