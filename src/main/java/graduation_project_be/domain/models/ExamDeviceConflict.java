package graduation_project_be.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents a pending device-conflict approval request.
 * Stored in Redis only (no DB table) — self-expiring after 5 minutes.
 *
 * Created when a student tries to start an exam session on a NEW device
 * while an existing session is already active on another device.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamDeviceConflict {

    /** Unique conflict ID (UUID). Used as the approval token. */
    private String conflictId;

    private Long examId;
    private Long studentId;

    /** Info about the existing (old) session */
    private String existingIpAddress;
    private String existingUserAgent;

    /** Info about the new (requesting) device */
    private String newIpAddress;
    private String newUserAgent;

    /** When the conflict was created (= when the student tried to join from new device) */
    private LocalDateTime requestedAt;

    /** Name of the student (for teacher display) */
    private String studentName;
    private String studentEmail;
}
