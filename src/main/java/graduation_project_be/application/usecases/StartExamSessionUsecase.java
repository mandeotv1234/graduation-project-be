package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
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
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamDeviceConflict;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.TeacherClass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

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
    private final DeviceConflictStore deviceConflictStore;
    private final DeviceConflictNotificationService deviceConflictNotificationService;
    private final UserRepository userRepository;
    private final ClassRepository classRepository;
    private final ExamDraftRepository examDraftRepository;


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

        // 3. Validate exam time window
        LocalDateTime now = LocalDateTime.now();
        if (exam.getStartTime() != null && now.isBefore(exam.getStartTime())) {
            throw new BadRequestException("Exam has not started yet");
        }
        if (exam.getEndTime() != null && now.isAfter(exam.getEndTime())) {
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
        Optional<LocalDateTime> existingStartTime = examSessionService.getExamStartTime(
                request.examId(), studentId);

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

            log.warn("Device conflict pending approval: exam={}, student={}, conflictId={}",
                    request.examId(), studentId, conflictId);

            return StartExamSessionResponse.conflictPending(conflictId,
                    "Tài khoản của bạn đang trong phiên thi ở một thiết bị khác. Vui lòng chờ giáo viên duyệt.");
        }

        // 6. Session started or same-device reconnect
        LocalDateTime examStartedAt;
        if (existingStartTime.isPresent()) {
            LocalDateTime candidateDeadline = existingStartTime.get().plusMinutes(exam.getDurationMinutes());
            if (exam.getEndTime() != null && exam.getEndTime().isBefore(candidateDeadline)) {
                candidateDeadline = exam.getEndTime();
            }
            boolean stillValid = Duration.between(now, candidateDeadline).getSeconds() > 0;
            if (stillValid) {
                String schemaName = String.format(STUDENT_SCHEMA_FORMAT, request.examId(), studentId);
                boolean hasSchemaObjects = !examSchemaService.extractMetadata(schemaName).isEmpty();

                if (hasSchemaObjects) {
                    examStartedAt = existingStartTime.get();
                } else {
                    examStartedAt = now;
                    initializeStudentSchemaForFreshStart(request.examId(), studentId, exam);
                    examSessionService.saveExamStartTime(request.examId(), studentId, examStartedAt);
                    examDraftRepository.deleteByExamIdAndStudentId(request.examId(), studentId);
                    log.info("Reinitialized empty student schema on start-session: exam={}, student={}",
                            request.examId(), studentId);
                }
            } else {
                examStartedAt = now;
                initializeStudentSchemaForFreshStart(request.examId(), studentId, exam);
                examSessionService.saveExamStartTime(request.examId(), studentId, examStartedAt);
                examDraftRepository.deleteByExamIdAndStudentId(request.examId(), studentId);
            }
        } else {
            examStartedAt = now;
            initializeStudentSchemaForFreshStart(request.examId(), studentId, exam);
            examSessionService.saveExamStartTime(request.examId(), studentId, examStartedAt);
            examDraftRepository.deleteByExamIdAndStudentId(request.examId(), studentId);
        }

        LocalDateTime examDeadline = examStartedAt.plusMinutes(exam.getDurationMinutes());
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

        boolean isLoadDdl = exam.getSettings() == null || exam.getSettings().getIsLoadDdl() == null
                || exam.getSettings().getIsLoadDdl();

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
