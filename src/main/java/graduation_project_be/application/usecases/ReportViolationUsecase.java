package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamViolationRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ViolationNotificationService;
import graduation_project_be.application.usecases.request.ReportViolationRequest;
import graduation_project_be.application.usecases.request.SubmitExamRequest;
import graduation_project_be.application.usecases.response.ReportViolationResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamDraft;
import graduation_project_be.domain.models.ExamViolation;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class ReportViolationUsecase {

    private static final int DEFAULT_MAX_VIOLATIONS = 100;

    private final ExamViolationRepository examViolationRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final ViolationNotificationService violationNotificationService;
    private final SubmitExamUsecase submitExamUsecase;
    private final ExamDraftRepository examDraftRepository;
    private final UserRepository userRepository;

    public ReportViolationResponse execute(ReportViolationRequest request) {
        User currentUser = currentUserService.getCurrentUser();
        Long studentId = currentUser.getId();
        String studentName = currentUser.getFullName();

        Exam exam = resolveEnrolledExam(request.examId(), studentId);

        return raiseViolation(exam, studentId, studentName, request.violationType(),
                request.description(), request.ipAddress(), request.userAgent());
    }

    /**
     * System-raised violation (no request context) — used by the heartbeat sweep for
     * INTEGRITY_TAMPERED. Resolves the student name from the repository instead of the
     * current-user context.
     */
    public ReportViolationResponse executeAsSystem(Long examId, Long studentId, String violationType,
                                                   String description) {
        Exam exam = resolveEnrolledExam(examId, studentId);

        User student = userRepository.findById(studentId).orElse(null);
        String studentName = student != null ? student.getFullName() : "Unknown";

        return raiseViolation(exam, studentId, studentName, violationType, description, null, null);
    }

    private Exam resolveEnrolledExam(Long examId, Long studentId) {
        Exam exam = examRepository.findByIdAndIsPublished(examId, true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), studentId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }
        return exam;
    }

    private ReportViolationResponse raiseViolation(Exam exam, Long studentId, String studentName,
                                                   String violationType, String description,
                                                   String ipAddress, String userAgent) {
        Long examId = exam.getId();

        long previousAttempts = examResultRepository.countByExamIdAndStudentId(examId, studentId);
        int currentAttempt = (int) previousAttempts + 1;

        int maxViolationsLimit = exam.getSettings() != null && exam.getSettings().getMaxViolations() != null
                ? exam.getSettings().getMaxViolations()
                : DEFAULT_MAX_VIOLATIONS;
        boolean enableAutoSubmit = exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getAutoSubmitOnViolation());

        // Block further violations once auto-submit already fired (only when auto-submit is enabled)
        long existingCount = examViolationRepository.countByExamIdAndStudentIdAndAttemptNumber(
                examId, studentId, currentAttempt);
        if (enableAutoSubmit && existingCount >= maxViolationsLimit) {
            throw new BadRequestException("Exam has already been auto-submitted due to maximum violations");
        }

        ExamViolation violation = ExamViolation.builder()
                .examId(examId)
                .studentId(studentId)
                .attemptNumber(currentAttempt)
                .violationType(violationType)
                .description(description)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build();

        ExamViolation saved = examViolationRepository.save(violation);
        long violationCount = existingCount + 1;

        boolean autoSubmitted = false;
        if (enableAutoSubmit && violationCount >= maxViolationsLimit) {
            autoSubmitted = true;
            log.warn("Student {} ({}) reached {} violations for exam {} — auto-submitting exam",
                    studentId, studentName, violationCount, examId);

            // Persist/send the final violation before the submission notification
            // so the "NỘP BÀI" notification remains the latest event.
            notifyTeachers(examId, exam, studentId, studentName, violationType, description,
                    currentAttempt, violationCount, autoSubmitted);
            try {
                autoSubmitExam(examId, studentId);
            } catch (Exception e) {
                log.error("Auto-submit failed for student {} exam {}: {}",
                        studentId, examId, e.getMessage());
            }
        } else {
            notifyTeachers(examId, exam, studentId, studentName, violationType, description,
                    currentAttempt, violationCount, autoSubmitted);
        }

        return ReportViolationResponse.fromModel(saved, violationCount, autoSubmitted);
    }

    private void notifyTeachers(Long examId, Exam exam, Long studentId, String studentName,
                                String violationType, String description,
                                int currentAttempt, long violationCount, boolean autoSubmitted) {
        violationNotificationService.notifyTeacher(
                examId, resolveTeacherIds(exam), studentId,
                studentName, violationType, description,
                currentAttempt, violationCount, autoSubmitted);
    }

    private void autoSubmitExam(Long examId, Long studentId) {
        // Fetch current draft to submit student's partial answers
        Optional<ExamDraft> draftOpt = examDraftRepository.findByExamIdAndStudentId(examId, studentId);
        List<SubmitExamRequest.AnswerItem> answers = draftOpt.map(draft -> 
                draft.getAnswers() == null ? List.<SubmitExamRequest.AnswerItem>of() : 
                draft.getAnswers().stream()
                        .map(da -> new SubmitExamRequest.AnswerItem(da.getQuestionId(), da.getContent() != null ? da.getContent() : ""))
                        .collect(Collectors.toList())
        ).orElse(List.of());

        // Auto-submit with currently saved answers -> SubmitExamUsecase saves submissions
        // and enqueues grading. Session cleanup is handled by grading worker.
        submitExamUsecase.executeAsSystem(new SubmitExamRequest(examId, answers, null, null), studentId, true);
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
}
