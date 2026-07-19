package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.BannedFromExamException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.DeviceConflictNotificationService;
import graduation_project_be.application.port.services.DeviceConflictStore;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.StartExamSessionRequest;
import graduation_project_be.application.usecases.response.StartExamSessionResponse;
import graduation_project_be.application.usecases.support.ExamDeadlinePolicy;
import graduation_project_be.application.usecases.support.ExamDeadlinePolicy.ExamDeadlines;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamDeviceConflict;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.TeacherClass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@Slf4j
@RequiredArgsConstructor
public class StartExamSessionUsecase {
    private static final String STUDENT_SCHEMA_FORMAT = "exam_%d_student_%d_att_%d";

    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSessionService examSessionService;
    private final ExamResultRepository examResultRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamSchemaService examSchemaService;
    private final DeviceConflictStore deviceConflictStore;
    private final DeviceConflictNotificationService deviceConflictNotificationService;
    private final UserRepository userRepository;
    private final ClassRepository classRepository;
    private final ExamDraftRepository examDraftRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ClassStudentBanRepository classStudentBanRepository;
    @Transactional
    public StartExamSessionResponse execute(StartExamSessionRequest request) {
        Long studentId = currentUserService.getCurrentUserId();

        // 1. Validate exam exists and is published
        Exam exam = examRepository.findByIdAndIsPublished(request.examId(), true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        // 2. Validate student is enrolled
        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), studentId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        // 2b. Check if student is banned from this class
        classStudentBanRepository.findActiveByClassIdAndStudentId(exam.getClassId(), studentId)
                .ifPresent(ban -> {
                    throw new BannedFromExamException("Bạn đã bị cấm thi trong lớp này: " +
                            (ban.getReason() != null ? ban.getReason() : "Không có lý do"));
                });

        Optional<LocalDateTime> existingStartTime = examSessionService.getExamStartTime(
                request.examId(), studentId);

        // 3. Validate exam time window
        LocalDateTime now = TimeUtils.now();
        if (exam.getStartTime() != null && now.isBefore(exam.getStartTime())) {
            throw new BadRequestException("Exam has not started yet");
        }
        boolean canResumeExistingSession = existingStartTime
                .map(startedAt -> !ExamDeadlinePolicy.calculate(exam, startedAt).isExpired(now))
                .orElse(false);
        if (exam.getEndTime() != null
                && !now.isBefore(exam.getEndTime())
                && !canResumeExistingSession) {
            throw new BadRequestException("Exam has already ended");
        }

        // 4. Validate maxAttempts
        if (exam.getMaxAttempts() != null && exam.getMaxAttempts() > 0) {
            long attemptCount = examResultRepository.countByExamIdAndStudentId(request.examId(), studentId);
            if (attemptCount >= exam.getMaxAttempts()) {
                throw new BadRequestException(
                        "Bạn đã hết số lần làm bài (" + exam.getMaxAttempts() + "/" + exam.getMaxAttempts() + ").");
            }
        }

        // 5. Check existing session and IP/UA
        boolean hadActiveDeviceSession = examSessionService.getActiveSession(request.examId(), studentId).isPresent();

        boolean started = examSessionService.tryStartSession(
                request.examId(), studentId, request.ipAddress(), request.userAgent());

        if (!started) {
            // Device conflict — create pending approval request
            String existingSessionValue = examSessionService.getRawSessionValue(request.examId(), studentId)
                    .orElse("|");
            String[] parts = existingSessionValue.split("\\|", 2);
            String existingIp = parts[0];
            String existingUa = parts.length > 1 ? parts[1] : "";

            User student = userRepository.findById(studentId).orElse(null);
            String studentName = student != null ? student.getFullName() : "Sinh vien #" + studentId;
            String studentEmail = student != null ? student.getEmail() : "";

            String conflictId = UUID.randomUUID().toString();
            ExamDeviceConflict conflict = ExamDeviceConflict.builder()
                    .conflictId(conflictId)
                    .examId(request.examId())
                    .studentId(studentId)
                    .existingIpAddress(existingIp)
                    .existingUserAgent(existingUa)
                    .newIpAddress(request.ipAddress())
                    .newUserAgent(request.userAgent())
                    .requestedAt(now)
                    .studentName(studentName)
                    .studentEmail(studentEmail)
                    .build();

            deviceConflictStore.save(conflict);

            // Notify ALL teachers associated with the exam's class
            List<Long> teacherIds = classRepository.findTeachersByClassId(exam.getClassId())
                    .stream()
                    .map(TeacherClass::getTeacherId)
                    .toList();

            deviceConflictNotificationService.notifyTeacherConflictPending(request.examId(), teacherIds, conflict);

            log.warn("Xung đột thiết bị đang chờ duyệt: exam={}, student={}, conflictId={}",
                    request.examId(), studentId, conflictId);

            return StartExamSessionResponse.conflictPending(conflictId,
                    "Tài khoản của bạn đang trong phiên thi ở một thiết bị khác. Vui lòng chờ giáo viên duyệt.");
        }

        // Compute next attempt number (completed attempts + 1)
        long completedAttempts = examResultRepository.countByExamIdAndStudentId(request.examId(), studentId);
        int nextAttempt = (int) completedAttempts + 1;

        // 6. Session started or same-device reconnect
        LocalDateTime examStartedAt;
        try {
            if (existingStartTime.isPresent()) {
                ExamDeadlines existingDeadlines = ExamDeadlinePolicy.calculate(exam, existingStartTime.get());
                if (!existingDeadlines.isExpired(now)) {
                    String schemaName = buildStudentSchemaName(request.examId(), studentId, nextAttempt);
                    if (isSchemaReadyForStart(schemaName, exam)) {
                        examStartedAt = existingStartTime.get();
                    } else {
                        examStartedAt = prepareFreshAttemptAndStartClock(
                                request.examId(), studentId, exam, nextAttempt);
                        log.info("Đã khởi tạo lại schema trống của sinh viên khi start-session: exam={}, student={}",
                                request.examId(), studentId);
                    }
                } else {
                    examStartedAt = prepareFreshAttemptAndStartClock(
                            request.examId(), studentId, exam, nextAttempt);
                }
            } else {
                examStartedAt = prepareFreshAttemptAndStartClock(
                        request.examId(), studentId, exam, nextAttempt);
            }
        } catch (RuntimeException exception) {
            if (!hadActiveDeviceSession) {
                examSessionService.clearSession(request.examId(), studentId);
            }
            throw exception;
        }

        LocalDateTime responseTime = TimeUtils.now();
        ExamDeadlines deadlines = ExamDeadlinePolicy.calculate(exam, examStartedAt);
        if (deadlines.isExpired(responseTime)) {
            throw new BadRequestException("Exam time has expired");
        }
        long remainingSeconds = ExamDeadlinePolicy.remainingSeconds(
                responseTime, deadlines.activeDeadline(responseTime));

        messagingTemplate.convertAndSend(
            "/topic/exam/" + request.examId() + "/violations",
            Map.of(
                "type", "SESSION_STATUS_CHANGED",
                "studentId", studentId,
                "examStatus", "IN_PROGRESS",
                "timestamp", responseTime.toString(),
                "autoSubmitted", false,
                "violationCount", 0
            )
        );

        return StartExamSessionResponse.success(
                responseTime, examStartedAt, deadlines.regularDeadline(), remainingSeconds, exam.getDurationMinutes());
    }

    private LocalDateTime prepareFreshAttemptAndStartClock(
            Long examId, Long studentId, Exam exam, int attemptNumber) {
        long preparationStartedAt = System.nanoTime();
        initializeStudentSchemaForFreshStart(examId, studentId, exam, attemptNumber);
        examDraftRepository.deleteByExamIdAndStudentId(examId, studentId);

        LocalDateTime readyAt = TimeUtils.now();
        if (exam.getEndTime() != null && !readyAt.isBefore(exam.getEndTime())) {
            throw new BadRequestException("Exam has already ended while preparing the exam environment");
        }

        examSessionService.saveExamStartTime(examId, studentId, readyAt);
        log.info(
                "Exam environment ready and clock started: exam={}, student={}, attempt={}, preparationMs={}, startedAt={}",
                examId, studentId, attemptNumber,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preparationStartedAt), readyAt);
        return readyAt;
    }

    private void initializeStudentSchemaForFreshStart(Long examId, Long studentId, Exam exam, int attemptNumber) {
        String schemaName = buildStudentSchemaName(examId, studentId, attemptNumber);
        long readinessCheckStartedAt = System.nanoTime();
        if (isSchemaReadyForStart(schemaName, exam)) {
            log.info(
                    "Schema [{}] already prepared before start-session, skipping reset/load (readinessCheckMs={})",
                    schemaName,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - readinessCheckStartedAt));
            return;
        }

        Long specificationId = exam.getSpecificationId();
        if (specificationId == null) {
            return;
        }

        ExamSpecification specification = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", specificationId));

        boolean shouldInitializeDatabase = shouldInitializeDatabase(exam);
        String ddlScript = shouldInitializeDatabase ? specification.getDdlScript() : null;
        String defaultDatasetScript = shouldInitializeDatabase
                ? resolveSeedDatasetScript(specification, exam.getSettings().getSeedDatasetId())
                : null;

        long schemaLoadStartedAt = System.nanoTime();
        examSchemaService.resetSchema(schemaName, false);
        examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, defaultDatasetScript);
        log.info(
                "Student schema prepared: schema={}, readinessCheckMs={}, resetAndLoadMs={}",
                schemaName,
                TimeUnit.NANOSECONDS.toMillis(schemaLoadStartedAt - readinessCheckStartedAt),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - schemaLoadStartedAt));
    }

    private String buildStudentSchemaName(Long examId, Long studentId, int attemptNumber) {
        return String.format(STUDENT_SCHEMA_FORMAT, examId, studentId, attemptNumber);
    }

    private boolean isSchemaReadyForStart(String schemaName, Exam exam) {
        if (!shouldInitializeDatabase(exam)) {
            return true;
        }
        return !examSchemaService.extractMetadata(schemaName).isEmpty();
    }

    private boolean shouldInitializeDatabase(Exam exam) {
        return exam.getSettings() != null && Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl());
    }

    private String resolveSeedDatasetScript(ExamSpecification specification, Long seedDatasetId) {
        if (seedDatasetId == null) {
            throw new BadRequestException("Đề thi yêu cầu nạp dataset nhưng chưa có seedDatasetId.");
        }

        if (specification.getDatasets() == null) {
            throw new BadRequestException("Exam requires a seed dataset, but the specification has no datasets.");
        }

        return specification.getDatasets().stream()
                .filter(SpecDataset::isActive)
                .filter(dataset -> seedDatasetId.equals(dataset.getId()))
                .map(SpecDataset::getDataScript)
                .filter(script -> script != null && !script.isBlank())
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Dataset dùng để nạp dữ liệu mẫu không tồn tại, đang tắt, hoặc không có data script."));
    }
}
