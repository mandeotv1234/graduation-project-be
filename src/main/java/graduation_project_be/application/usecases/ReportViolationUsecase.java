package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamViolationRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
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
        int maxViolationsLimit = exam.getSettings() != null && exam.getSettings().getMaxViolations() != null
                ? exam.getSettings().getMaxViolations()
                : DEFAULT_MAX_VIOLATIONS;
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

            // Persist/send the final violation before the submission notification
            // so the "NỘP BÀI" notification remains the latest event.
            notifyTeachers(request, exam, studentId, studentName, currentAttempt, violationCount, autoSubmitted);
            try {
                autoSubmitExam(request.examId(), studentId);
            } catch (Exception e) {
                log.error("Auto-submit failed for student {} exam {}: {}",
                        studentId, request.examId(), e.getMessage());
            }
        } else {
            notifyTeachers(request, exam, studentId, studentName, currentAttempt, violationCount, autoSubmitted);
        }

        return ReportViolationResponse.fromModel(saved, violationCount, autoSubmitted);
    }

    private void notifyTeachers(ReportViolationRequest request, Exam exam, Long studentId, String studentName,
                                int currentAttempt, long violationCount, boolean autoSubmitted) {
        violationNotificationService.notifyTeacher(
                request.examId(), resolveTeacherIds(exam), studentId,
                studentName, request.violationType(), request.description(),
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
