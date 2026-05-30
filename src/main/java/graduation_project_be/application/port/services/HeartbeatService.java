package graduation_project_be.application.port.services;

import java.util.List;
import java.util.Optional;

/**
 * Tracks per-student exam heartbeats so the server can detect anti-cheat tampering:
 * a stopped/forged heartbeat stream is the signal that client integrity scripts were killed.
 * State is held in Redis; detection logic lives in the heartbeat usecase + sweep job.
 */
public interface HeartbeatService {

    /** lastSeenEpochMs uses server time; tamperStreak counts consecutive anomalies; flagged guards against duplicate violations. */
    record HeartbeatState(long lastSeenEpochMs, int lastSeq, int tamperStreak, boolean flagged) {}

    record HeartbeatKey(Long examId, Long studentId) {}

    Optional<HeartbeatState> get(Long examId, Long studentId);

    void save(Long examId, Long studentId, HeartbeatState state);

    void clear(Long examId, Long studentId);

    /** All currently tracked heartbeats — used by the scheduled absence sweep. */
    List<HeartbeatKey> scanActive();
}
