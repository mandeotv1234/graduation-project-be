package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ConflictException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.StartExamSessionRequest;
import graduation_project_be.application.usecases.response.StartExamSessionResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@RequiredArgsConstructor
public class StartExamSessionUsecase {

    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSessionService examSessionService;

    public StartExamSessionResponse execute(StartExamSessionRequest request) {
        Long studentId = currentUserService.getCurrentUserId();

        // Validate exam exists and is published
        Exam exam = examRepository.findByIdAndIsPublished(request.examId(), true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        // Validate student is enrolled
        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), studentId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        // Validate exam time window (if startTime/endTime are set)
        LocalDateTime now = LocalDateTime.now();
        if (exam.getStartTime() != null && now.isBefore(exam.getStartTime())) {
            throw new BadRequestException("Exam has not started yet");
        }
        if (exam.getEndTime() != null && now.isAfter(exam.getEndTime())) {
            throw new BadRequestException("Exam has already ended");
        }

        // Check existing start time BEFORE creating/refreshing the session.
        // This way we can tell: if the key existed before, it's a same-device
        // reconnect.
        // If not (key absent or stale from a previous wiped session), it's a fresh
        // start.
        Optional<LocalDateTime> existingStartTime = examSessionService.getExamStartTime(
                request.examId(), studentId);

        // Try to start session (single-device enforcement)
        boolean started = examSessionService.tryStartSession(
                request.examId(), studentId, request.ipAddress(), request.userAgent());

        if (!started) {
            throw new ConflictException(
                    "ExamSession", "examId_studentId",
                    request.examId() + "_" + studentId);
        }

        // Determine effective exam start time.
        // Reuse the previous start time only if it still leaves positive remaining time
        // (guards against stale keys left after a Redis exam_session flush).
        LocalDateTime examStartedAt;
        if (existingStartTime.isPresent()) {
            LocalDateTime candidateDeadline = existingStartTime.get().plusMinutes(exam.getDurationMinutes());
            if (exam.getEndTime() != null && exam.getEndTime().isBefore(candidateDeadline)) {
                candidateDeadline = exam.getEndTime();
            }
            boolean stillValid = Duration.between(now, candidateDeadline).getSeconds() > 0;
            if (stillValid) {
                // Valid reconnect — preserve the original start time
                examStartedAt = existingStartTime.get();
            } else {
                // Stale start-time key (exam had already expired) — reset to now
                examStartedAt = now;
                examSessionService.saveExamStartTime(request.examId(), studentId, examStartedAt);
            }
        } else {
            // First time starting — record the backend start time
            examStartedAt = now;
            examSessionService.saveExamStartTime(request.examId(), studentId, examStartedAt);
        }

        // Calculate remaining seconds from backend
        LocalDateTime examDeadline = examStartedAt.plusMinutes(exam.getDurationMinutes());

        // If exam has a hard end time, use the earlier of the two
        if (exam.getEndTime() != null && exam.getEndTime().isBefore(examDeadline)) {
            examDeadline = exam.getEndTime();
        }

        long remainingSeconds = Duration.between(now, examDeadline).getSeconds();
        if (remainingSeconds <= 0) {
            throw new BadRequestException("Exam time has expired");
        }

        return StartExamSessionResponse.success(
                now, examStartedAt, examDeadline, remainingSeconds, exam.getDurationMinutes());
    }
}
