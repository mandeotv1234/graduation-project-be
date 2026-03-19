package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamViolationRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.ViolationNotificationService;
import graduation_project_be.application.usecases.request.ReportViolationRequest;
import graduation_project_be.application.usecases.request.SubmitExamRequest;
import graduation_project_be.application.usecases.response.ReportViolationResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamViolation;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ReportViolationUsecase {

    private static final int DEFAULT_MAX_VIOLATIONS = 100;

    private final ExamViolationRepository examViolationRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ViolationNotificationService violationNotificationService;
    private final SubmitExamUsecase submitExamUsecase;
    private final ExamSessionService examSessionService;

    public ReportViolationResponse execute(ReportViolationRequest request) {
        User currentUser = currentUserService.getCurrentUser();
        Long studentId = currentUser.getId();
        String studentName = currentUser.getFullName();

        // Validate exam exists and is published
        Exam exam = examRepository.findByIdAndIsPublished(request.examId(), true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        // Validate student is enrolled in the exam's class
        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), studentId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        // Determine current attempt
        long previousAttempts = examResultRepository.countByExamIdAndStudentId(request.examId(), studentId);
        int currentAttempt = (int) previousAttempts + 1;

        // Determine max violations limit
        int maxViolationsLimit = DEFAULT_MAX_VIOLATIONS;
        boolean enableAutoSubmit = exam.getSettings() != null && Boolean.TRUE.equals(exam.getSettings().getAutoSubmitOnViolation());

        // Check if already auto-submitted (prevent further violations only if auto-submit is enabled)
        long existingCount = examViolationRepository.countByExamIdAndStudentIdAndAttemptNumber(request.examId(), studentId, currentAttempt);
        if (enableAutoSubmit && existingCount >= maxViolationsLimit) {
            throw new BadRequestException("Exam has already been auto-submitted due to maximum violations");
        }

        // Save violation
        ExamViolation violation = ExamViolation.builder()
                .examId(request.examId())
                .studentId(studentId)
                .attemptNumber(currentAttempt)
                .violationType(request.violationType())
                .description(request.description())
                .ipAddress(request.ipAddress())
                .userAgent(request.userAgent())
                .build();

        ExamViolation saved = examViolationRepository.save(violation);
        long violationCount = existingCount + 1;

        // Check if max violations reached -> auto-submit
        boolean autoSubmitted = false;
        if (enableAutoSubmit && violationCount >= maxViolationsLimit) {
            autoSubmitted = true;
            log.warn("Student {} ({}) reached {} violations for exam {} — auto-submitting exam",
                    studentId, studentName, violationCount, request.examId());
            try {
                autoSubmitExam(request.examId(), studentId);
            } catch (Exception e) {
                log.error("Auto-submit failed for student {} exam {}: {}",
                        studentId, request.examId(), e.getMessage());
            }
        }

        // Notify teacher via WebSocket
        violationNotificationService.notifyTeacher(
                request.examId(), exam.getCreatorId(), studentId,
                studentName, request.violationType(), request.description(),
                violationCount, autoSubmitted);

        return ReportViolationResponse.fromModel(saved, violationCount, autoSubmitted);
    }

    private void autoSubmitExam(Long examId, Long studentId) {
        // Auto-submit with empty answers — the SubmitExamUsecase will grade
        // any previously saved work
        submitExamUsecase.execute(new SubmitExamRequest(examId, List.of()));

        // End the session
        examSessionService.endSession(examId, studentId);
    }
}
