package graduation_project_be.application.port.services;

import graduation_project_be.domain.models.ExamDeviceConflict;

import java.util.Optional;

/**
 * Port for storing and retrieving pending device-conflict requests.
 * Backed by Redis with a short TTL (5 minutes).
 */
public interface DeviceConflictStore {

    /** Persist the conflict. Overwrites any previous conflict for the same (examId, studentId). */
    void save(ExamDeviceConflict conflict);

    /** Retrieve by conflictId token. Returns empty if expired or not found. */
    Optional<ExamDeviceConflict> findByConflictId(String conflictId);

    /** Delete after resolution (approve or reject). */
    void delete(String conflictId);
}
