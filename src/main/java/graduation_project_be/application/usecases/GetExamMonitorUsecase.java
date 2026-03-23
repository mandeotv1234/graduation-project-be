package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamViolationRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GetExamMonitorRequest;
import graduation_project_be.application.usecases.response.GetExamMonitorResponse;
import graduation_project_be.application.usecases.response.GetExamMonitorStudentResponse;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamViolation;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

        boolean autoSubmitEnabled = exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getAutoSubmitOnViolation());

        List<GetExamMonitorStudentResponse> studentResponses = students.stream()
                .sorted(Comparator.comparing(User::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(student -> {
                    List<ExamViolation> violations = violationsByStudent.getOrDefault(student.getId(), List.of());
                    int violationCount = violations.size();
                    ExamViolation latestViolation = violations.isEmpty() ? null : violations.get(0);
                    boolean autoSubmitted = autoSubmitEnabled && violationCount >= DEFAULT_MAX_VIOLATIONS;
                    String status = autoSubmitted ? "AUTO_SUBMITTED"
                            : violationCount > 0 ? "VIOLATING" : "NORMAL";

                    return new GetExamMonitorStudentResponse(
                            student.getId(),
                            student.getEmail(),
                            student.getFullName(),
                            violationCount,
                            latestViolation != null ? latestViolation.getViolationType() : null,
                            latestViolation != null ? latestViolation.getDescription() : null,
                            latestViolation != null ? latestViolation.getCreatedAt() : null,
                            autoSubmitted,
                            status);
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
