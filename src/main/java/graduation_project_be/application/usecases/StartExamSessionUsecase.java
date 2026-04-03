package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ConflictException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.StartExamSessionRequest;
import graduation_project_be.application.usecases.response.StartExamSessionResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class StartExamSessionUsecase {
    private static final String STUDENT_SCHEMA_FORMAT = "exam_%d_student_%d";

    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSessionService examSessionService;
    private final ExamResultRepository examResultRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamSchemaService examSchemaService;

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

        // Validate maxAttempts — block student if they have exhausted all attempts
        if (exam.getMaxAttempts() != null && exam.getMaxAttempts() > 0) {
            long attemptCount = examResultRepository.countByExamIdAndStudentId(request.examId(), studentId);
            if (attemptCount >= exam.getMaxAttempts()) {
                throw new BadRequestException(
                        "Bạn đã hết số lần làm bài (" + exam.getMaxAttempts() + "/" + exam.getMaxAttempts() + ").");
            }
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
                String schemaName = String.format(STUDENT_SCHEMA_FORMAT, request.examId(), studentId);
                boolean hasSchemaObjects = !examSchemaService.extractMetadata(schemaName).isEmpty();

                if (hasSchemaObjects) {
                    examStartedAt = existingStartTime.get();
                } else {
                    // A stale start-time key can survive while the schema has been dropped
                    // (e.g. previous attempt finished). Re-initialize and reset timer.
                    examStartedAt = now;
                    initializeStudentSchemaForFreshStart(request.examId(), studentId, exam);
                    examSessionService.saveExamStartTime(request.examId(), studentId, examStartedAt);
                    log.info("Reinitialized empty student schema on start-session: exam={}, student={}",
                            request.examId(), studentId);
                }
            } else {
                // Stale start-time key (exam had already expired) — reset to now
                examStartedAt = now;
                initializeStudentSchemaForFreshStart(request.examId(), studentId, exam);
                examSessionService.saveExamStartTime(request.examId(), studentId, examStartedAt);
            }
        } else {
            // First time starting — record the backend start time
            examStartedAt = now;
            initializeStudentSchemaForFreshStart(request.examId(), studentId, exam);
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

    private void initializeStudentSchemaForFreshStart(Long examId, Long studentId, Exam exam) {
        Long specificationId = exam.getSpecificationId();
        if (specificationId == null) {
            return;
        }

        ExamSpecification specification = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", specificationId));

        boolean isLoadDdl = exam.getSettings() == null || exam.getSettings().getIsLoadDdl() == null || exam.getSettings().getIsLoadDdl();
        
        String ddlScript = isLoadDdl ? specification.getDdlScript() : null;

        String defaultDatasetScript = (isLoadDdl && specification.getDatasets() != null)
                ? specification.getDatasets().stream()
                        .filter(SpecDataset::isActive)
                        .sorted(Comparator.comparingInt(SpecDataset::getOrderIndex))
                        .map(SpecDataset::getDataScript)
                        .filter(script -> script != null && !script.isBlank())
                        .findFirst()
                        .orElse(null)
                : null;
        
        String schemaName = String.format(STUDENT_SCHEMA_FORMAT, examId, studentId);
        examSchemaService.resetSchema(schemaName);

        examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, defaultDatasetScript);
    }
}
