package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamViolationRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.GetExamMonitorRequest;
import graduation_project_be.application.usecases.response.GetExamMonitorResponse;
import graduation_project_be.application.usecases.response.GetExamMonitorStudentResponse;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamViolation;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetExamMonitorUsecase {

    private static final int DEFAULT_MAX_VIOLATIONS = 100;

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final UserRepository userRepository;
    private final ExamViolationRepository examViolationRepository;
    private final CurrentUserService currentUserService;
    private final ExamSessionService examSessionService;
    private final ExamResultRepository examResultRepository;
    private final ExamDraftRepository examDraftRepository;

    public GetExamMonitorResponse execute(GetExamMonitorRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("You do not have access to this exam");
        }

        var classInfo = classRepository.findById(exam.getClassId());
        List<ClassEnrollment> enrollments = classEnrollmentRepository.findByClassId(exam.getClassId());

        if (enrollments.isEmpty()) {
            return new GetExamMonitorResponse(
                    exam.getId(),
                    exam.getTitle(),
                    exam.getClassId(),
                    classInfo.getClassCode(),
                    exam.getStartTime(),
                    exam.getEndTime(),
                    exam.getIsPublished(),
                    0,
                    0,
                    List.of());
        }

        List<Long> studentIds = enrollments.stream()
                .map(ClassEnrollment::getStudentId)
                .toList();

        List<User> students = userRepository.findByIdIn(studentIds, studentIds.size(), 0);
        Map<Long, List<ExamViolation>> violationsByStudent = examViolationRepository.findByExamId(exam.getId())
                .stream()
                .collect(Collectors.groupingBy(ExamViolation::getStudentId));

        Set<Long> activeStudentIds = examSessionService.getActiveStudentIds(exam.getId());
        List<ExamResult> results = examResultRepository.findByExamId(exam.getId());
        Map<Long, List<ExamResult>> resultsByStudent = results.stream()
                .collect(Collectors.groupingBy(ExamResult::getStudentId));
        
        Set<Long> draftedStudentIds = examDraftRepository.findByExamId(exam.getId()).stream()
                .map(graduation_project_be.domain.models.ExamDraft::getStudentId)
                .collect(Collectors.toSet());

        boolean autoSubmitEnabled = exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getAutoSubmitOnViolation());

        List<GetExamMonitorStudentResponse> studentResponses = students.stream()
                .sorted(Comparator.comparing(User::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(student -> {
                    List<ExamViolation> violations = violationsByStudent.getOrDefault(student.getId(), List.of());
                    int violationCount = violations.size();
                    ExamViolation latestViolation = violations.isEmpty() ? null : violations.get(0);
                    boolean autoSubmitted = autoSubmitEnabled && violationCount >= (exam.getSettings().getMaxViolations() != null ? exam.getSettings().getMaxViolations() : DEFAULT_MAX_VIOLATIONS);
                    String status = autoSubmitted ? "AUTO_SUBMITTED"
                            : violationCount > 0 ? "VIOLATING" : "NORMAL";

                    // Determine examStatus
                    boolean isActive = activeStudentIds.contains(student.getId()) || draftedStudentIds.contains(student.getId());
                    List<ExamResult> studentResults = resultsByStudent.getOrDefault(student.getId(), List.of());
                    int maxAttempts = exam.getMaxAttempts() != null ? exam.getMaxAttempts() : 1;
                    boolean isUnlimitedAttempts = exam.getMaxAttempts() == null;
                    
                    String examStatus = "NOT_STARTED";
                    if (isActive && (isUnlimitedAttempts || studentResults.size() < maxAttempts)) {
                        examStatus = "IN_PROGRESS";
                    } else if (!isUnlimitedAttempts && studentResults.size() >= maxAttempts) {
                        examStatus = "SUBMITTED";
                        if (autoSubmitted) {
                            examStatus = "AUTO_SUBMITTED";
                        }
                    } else if (isUnlimitedAttempts && !studentResults.isEmpty() && !isActive) {
                        examStatus = "SUBMITTED"; 
                    } else if (!studentResults.isEmpty()) {
                        // User has submissions but hasn't reached max attempts, 
                        // could be NOT_STARTED on their next attempt or they are done.
                        // Let's call it SUBMITTED since they aren't actively taking it right now.
                        examStatus = "SUBMITTED";
                    }

                    return new GetExamMonitorStudentResponse(
                            student.getId(),
                            student.getEmail(),
                            student.getFullName(),
                            violationCount,
                            latestViolation != null ? latestViolation.getViolationType() : null,
                            latestViolation != null ? latestViolation.getDescription() : null,
                            latestViolation != null ? latestViolation.getCreatedAt() : null,
                            autoSubmitted,
                            status,
                            examStatus);
                })
                .toList();

        int totalViolators = (int) studentResponses.stream()
                .filter(item -> item.violationCount() > 0)
                .count();

        return new GetExamMonitorResponse(
                exam.getId(),
                exam.getTitle(),
                exam.getClassId(),
                classInfo.getClassCode(),
                exam.getStartTime(),
                exam.getEndTime(),
                exam.getIsPublished(),
                studentResponses.size(),
                totalViolators,
                studentResponses);
    }
}
