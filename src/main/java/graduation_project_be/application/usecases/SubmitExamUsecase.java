package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.GradingQueueService;
import graduation_project_be.application.port.services.HeartbeatService;
import graduation_project_be.application.usecases.request.SubmitExamRequest;
import graduation_project_be.application.usecases.response.SubmitExamResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.domain.models.TeacherNotification;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Submit exam usecase — now LIGHTWEIGHT.
 * Only saves student's SQL text to DB and enqueues a grading job.
 * Actual grading is performed asynchronously by GradeExamUsecase via the
 * grading queue.
 */
@Slf4j
@RequiredArgsConstructor
public class SubmitExamUsecase {

    /** Grace period (seconds) to account for network latency */
    private static final long SUBMIT_GRACE_SECONDS = 30;

    private final ExamRepository examRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamResultRepository examResultRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final ExamSessionService examSessionService;
    private final GradingQueueService gradingQueueService;
    private final ExamDraftRepository examDraftRepository;
    private final TeacherNotificationRepository teacherNotificationRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final HeartbeatService heartbeatService;
    @Transactional
    public SubmitExamResponse execute(SubmitExamRequest request) {
        Long studentId = currentUserService.getCurrentUserId();

        // Validate session fingerprint (only for manual submissions)
        // Note: request.ipAddress and userAgent are now passed from the Controller via
        // HttpServletRequest
        try {
            if (!examSessionService.isSessionValid(request.examId(), studentId, request.ipAddress(),
                    request.userAgent())) {
                // If session is missing but exam is already submitted, it means auto-submit
                // probably just finished.
                // We should return the existing result instead of throwing 401.
                boolean alreadySubmitted = examResultRepository.findByExamIdAndStudentId(request.examId(), studentId)
                        .isPresent();
                if (alreadySubmitted) {
                    log.info(
                            "Session invalid but exam {} already submitted for student {}. Returning successful status.",
                            request.examId(), studentId);
                    // Return a "fake" successful response so FE doesn't logout
                    return new SubmitExamResponse(
                            request.examId(), studentId, TimeUtils.now(), GradingStatus.COMPLETED,
                            BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, null, null);
                }
                throw new UnauthorizedException(
                        "Phiên thi không hợp lệ hoặc đã được thay thế bởi thiết bị khác. Vui lòng làm mới trang.");
            }
        } catch (UnauthorizedException e) {
            // Re-check submission state one last time in case of race condition
            if (examResultRepository.findByExamIdAndStudentId(request.examId(), studentId).isPresent()) {
                return new SubmitExamResponse(
                        request.examId(), studentId, TimeUtils.now(), GradingStatus.COMPLETED,
                        BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, null, null);
            }
            throw e;
        }

        return executeAsSystem(request, studentId, false);
    }

    @Transactional
    public SubmitExamResponse executeAsSystem(SubmitExamRequest request, Long studentId, boolean isAutoSubmit) {
        Long examId = request.examId();
        LocalDateTime submittedAt = TimeUtils.now();

        // 1. Validate exam
        Exam exam = examRepository.findByIdAndIsPublished(examId, true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        // 2. Validate enrollment
        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), studentId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        // 3. Backend time validation — prevent DevTools time manipulation
        long lateDurationSeconds = validateExamTime(examId, studentId, exam, submittedAt, isAutoSubmit);

        // 4. Load all questions for this exam (for validation only)
        List<ExamQuestion> allQuestions = examQuestionRepository.findByExamId(examId);
        Map<Long, ExamQuestion> questionMap = allQuestions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));

        BigDecimal maxScore = allQuestions.stream()
                .map(ExamQuestion::getPoints)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalQuestions = allQuestions.size();

        // 5. Validate submitted answers: no duplicates, all questionIds must belong to
        // this exam
        Map<Long, String> answerMap = new LinkedHashMap<>();
        for (SubmitExamRequest.AnswerItem answer : request.answers()) {
            if (!questionMap.containsKey(answer.questionId())) {
                throw new IllegalArgumentException("Question " + answer.questionId() + " does not belong to this exam");
            }
            if (answerMap.containsKey(answer.questionId())) {
                throw new IllegalArgumentException("Duplicate answer for question " + answer.questionId());
            }
            answerMap.put(answer.questionId(), answer.studentQuery());
        }

        // 6. Determine the current attempt number (1-based)
        long previousAttempts = examResultRepository.countByExamIdAndStudentId(examId, studentId);

        // Validate maxAttempts
        if (exam.getMaxAttempts() != null && exam.getMaxAttempts() > 0) {
            if (previousAttempts >= exam.getMaxAttempts()) {
                throw new BadRequestException(
                        "Bạn đã hết số lần làm bài (" + previousAttempts + "/" + exam.getMaxAttempts() + ").");
            }
        }

        int attemptNumber = (int) previousAttempts + 1;
        String schemaName = String.format("exam_%d_student_%d_att_%d", examId, studentId, attemptNumber);

        // 7. Save all answers to DB with status PENDING (fast — only text INSERT)
        for (ExamQuestion question : allQuestions) {
            String studentQuery = answerMap.get(question.getId());
            ExamSubmission submission = ExamSubmission.builder()
                    .examId(examId)
                    .questionId(question.getId())
                    .studentId(studentId)
                    .attemptNumber(attemptNumber)
                    .assignedSchemaName(schemaName)
                    .studentQuery(studentQuery != null ? studentQuery : "")
                    .isCorrect(null) // not graded yet
                    .scoreEarned(null) // not graded yet
                    .errorMessage(null)
                    .executionTimeMs(null)
                    .status(SubmissionStatus.PENDING)
                    .submittedAt(submittedAt)
                    .build();
            examSubmissionRepository.save(submission);
        }

        // 8. Create ExamResult with status PENDING (placeholder — will be updated by
        // grading worker)
        ExamResult examResult = ExamResult.builder()
                .examId(examId)
                .studentId(studentId)
                .attemptNumber(attemptNumber)
                .totalScore(BigDecimal.ZERO)
                .maxScore(maxScore)
                .totalQuestions(totalQuestions)
                .correctCount(0)
                .lateDurationSeconds((int) lateDurationSeconds)
                .submittedAt(submittedAt)
                .status(GradingStatus.PENDING)
                .build();
        examResultRepository.save(examResult);

        User student = userRepository.findById(studentId).orElse(null);
        String studentName = student != null ? student.getFullName() : "Unknown";
        List<Long> teacherIds = resolveTeacherIds(exam);

        // 9a. Save notification to DB WITHIN the same transaction
        //     so that /unread-count API always sees it after commit.
        saveSubmissionNotifications(exam, studentId, studentName, teacherIds, attemptNumber);

        // 9b. Enqueue grading job and send WebSocket AFTER DB commit
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                gradingQueueService.enqueue(examId, studentId, attemptNumber);
                log.info("Grading job enqueued: exam={}, student={}, attempt={}", examId, studentId, attemptNumber);
                sendSubmissionWebSocket(exam, studentId, studentName, teacherIds, attemptNumber);
            }
        });

        // 10. CLEAR THE SESSION! So next attempt (if any) starts with a fresh timer.
        examSessionService.clearSession(examId, studentId);

        // Stop heartbeat tracking so the absence sweep doesn't flag a submitted student.
        heartbeatService.clear(examId, studentId);

        // 11. CLEAR THE DRAFT! So next attempt starts with an empty answer set.
        examDraftRepository.deleteByExamIdAndStudentId(examId, studentId);

        // 10. Check if we need to return detailed answers
        List<SubmitExamResponse.SubmissionDetail> details = null;
        if (exam.getSettings() != null && Boolean.TRUE.equals(exam.getSettings().getShowResultAfterSubmit())) {
            details = allQuestions.stream().map(q -> new SubmitExamResponse.SubmissionDetail(
                    q.getId(),
                    q.getContent(),
                    q.getPoints(),
                    answerMap.getOrDefault(q.getId(), ""))).collect(Collectors.toList());
        }

        // 11. Return immediately — student sees "Đang chấm điểm..."
        messagingTemplate.convertAndSend(
            "/topic/exam/" + examId + "/violations",
            Map.of(
                "type", "SESSION_STATUS_CHANGED",
                "studentId", studentId,
                "examStatus", "SUBMITTED",
                "timestamp", submittedAt.toString(),
                "autoSubmitted", isAutoSubmit,
                "violationCount", 0
            )
        );

        return new SubmitExamResponse(
                examId, studentId, submittedAt, GradingStatus.PENDING,
                BigDecimal.ZERO, maxScore, 0, totalQuestions,
                details, null);
    }

    /**
     * Save submission notifications to DB — runs inside the main @Transactional
     * so the records commit atomically with the exam submission.
     */
    private void saveSubmissionNotifications(Exam exam, Long studentId, String studentName, List<Long> teacherIds, int attemptNumber) {
        if (teacherIds == null || teacherIds.isEmpty()) {
            return;
        }

        String description = String.format("Đã nộp bài lần %d. Đang chấm điểm.", attemptNumber);
        List<TeacherNotification> notifications = teacherIds.stream()
                .distinct()
                .map(teacherId -> TeacherNotification.builder()
                        .teacherId(teacherId)
                        .examId(exam.getId())
                        .studentId(studentId)
                        .studentName(studentName)
                        .violationType("NỘP BÀI")
                        .description(description)
                        .violationCount(0)
                        .autoSubmitted(false)
                        .isRead(false)
                        .createdAt(TimeUtils.now())
                        .build())
                .toList();

        teacherNotificationRepository.saveAll(notifications);
        log.info("Saved {} submission notifications to DB for exam={}, student={}",
                notifications.size(), exam.getId(), studentId);
    }

    /**
     * Send WebSocket notification to teachers — runs in afterCommit()
     * so teachers only see it after DB has committed.
     */
    private void sendSubmissionWebSocket(Exam exam, Long studentId, String studentName, List<Long> teacherIds, int attemptNumber) {
        if (teacherIds == null || teacherIds.isEmpty()) {
            return;
        }

        String message = String.format("Đã nộp bài lần %d. Đang chấm điểm.", attemptNumber);
        messagingTemplate.convertAndSend(
                "/topic/teacher/grading-results",
                Map.of(
                        "examId", exam.getId(),
                        "examName", exam.getTitle() != null ? exam.getTitle() : ("Exam " + exam.getId()),
                        "teacherIds", teacherIds,
                        "studentId", studentId,
                        "studentName", studentName,
                        "attemptNumber", attemptNumber,
                        "totalScore", BigDecimal.ZERO,
                        "maxScore", BigDecimal.ZERO,
                        "status", "SUBMITTED",
                        "message", message));
    }

    private List<Long> resolveTeacherIds(Exam exam) {
        List<Long> teacherIds = new ArrayList<>(classRepository.findTeachersByClassId(exam.getClassId())
                .stream()
                .map(TeacherClass::getTeacherId)
                .toList());

        if (exam.getCreatorId() != null) {
            teacherIds.add(exam.getCreatorId());
        }

        return teacherIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    // ========== Backend time validation ==========

    private long validateExamTime(Long examId, Long studentId, Exam exam, LocalDateTime submittedAt,
            boolean isAutoSubmit) {
        Optional<LocalDateTime> startTimeOpt = examSessionService.getExamStartTime(examId, studentId);

        if (startTimeOpt.isPresent()) {
            LocalDateTime examStartedAt = startTimeOpt.get();
            LocalDateTime examDeadline = examStartedAt.plusMinutes(exam.getDurationMinutes());

            if (exam.getEndTime() != null && exam.getEndTime().isBefore(examDeadline)) {
                examDeadline = exam.getEndTime();
            }

            long secondsOverdue = Duration.between(examDeadline, submittedAt).getSeconds();

            boolean allowOvertime = exam.getSettings() != null
                    && Boolean.TRUE.equals(exam.getSettings().getAllowOvertime());
            int lateThresholdMinutes = exam.getLateThreshold() != null ? exam.getLateThreshold() : 0;

            if (secondsOverdue > SUBMIT_GRACE_SECONDS) {
                if (allowOvertime && lateThresholdMinutes > 0) {
                    long lateThresholdSeconds = (long) lateThresholdMinutes * 60;
                    if (secondsOverdue <= lateThresholdSeconds) {
                        log.info(
                                "Late submission accepted within lateThreshold: exam={}, student={}, overdue={}s, threshold={}min",
                                examId, studentId, secondsOverdue, lateThresholdMinutes);
                        return secondsOverdue;
                    }

                    if (isAutoSubmit) {
                        log.info("Auto-submit processing overdue exam: exam={}, student={}, overdue={}s", examId,
                                studentId, secondsOverdue);
                        return secondsOverdue;
                    }

                    log.warn("Late submission rejected: exam={}, student={}, overdue={}s exceeds lateThreshold={}min",
                            examId, studentId, secondsOverdue, lateThresholdMinutes);
                    throw new BadRequestException(
                            "Thời gian nộp bài trễ đã vượt quá ngưỡng cho phép (" + lateThresholdMinutes + " phút).");
                }

                if (isAutoSubmit) {
                    log.info("Auto-submit processing overdue exam: exam={}, student={}, overdue={}s", examId, studentId,
                            secondsOverdue);
                    return secondsOverdue;
                }

                log.warn("Late submission rejected: exam={}, student={}, overdue={}s (grace={}s)",
                        examId, studentId, secondsOverdue, SUBMIT_GRACE_SECONDS);
                throw new BadRequestException(
                        "Exam time has expired. Submission was " + secondsOverdue + " seconds late.");
            }

            if (secondsOverdue > 0) {
                log.info("Late submission accepted within grace period: exam={}, student={}, overdue={}s",
                        examId, studentId, secondsOverdue);
                return secondsOverdue;
            }
        } else {
            log.warn("No backend start time found for exam={}, student={}. Allowing submission.",
                    examId, studentId);
        }

        return 0L;
    }
}
