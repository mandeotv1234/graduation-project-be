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
import graduation_project_be.domain.models.ExamDraft;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamViolation;
import graduation_project_be.domain.models.User;
import graduation_project_be.shared.utils.TimeUtils;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
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
                .map(ExamDraft::getStudentId)
                .collect(Collectors.toSet());

        boolean autoSubmitEnabled = exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getAutoSubmitOnViolation());

        List<GetExamMonitorStudentResponse> studentResponses = students.stream()
                .sorted(Comparator.comparing(User::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(student -> {
                    int maxAttempts = exam.getMaxAttempts() != null ? exam.getMaxAttempts() : 1;
                    boolean isUnlimitedAttempts = exam.getMaxAttempts() == null;
                    List<ExamResult> studentResults = resultsByStudent.getOrDefault(student.getId(), List.of());

                    boolean hasActiveFlags = activeStudentIds.contains(student.getId()) || draftedStudentIds.contains(student.getId());
                    boolean canTakeMore = isUnlimitedAttempts || studentResults.size() < maxAttempts;

                    boolean isCurrentlyTaking = hasActiveFlags && canTakeMore;

                    int targetAttempt = studentResults.size() + (isCurrentlyTaking ? 1 : 0);

                    List<ExamViolation> violations = violationsByStudent.getOrDefault(student.getId(), List.of());
                    int violationCount = 0;
                    ExamViolation latestViolation = null;

                    if (targetAttempt > 0 && !violations.isEmpty()) {
                        List<ExamViolation> latestAttemptViolations = violations.stream()
                                .filter(v -> v.getAttemptNumber() == targetAttempt)
                                .sorted(Comparator.comparing(ExamViolation::getCreatedAt).reversed())
                                .toList();

                        violationCount = latestAttemptViolations.size();
                        if (violationCount > 0) {
                            latestViolation = latestAttemptViolations.get(0);
                        }
                    }

                    boolean autoSubmitted = autoSubmitEnabled && violationCount >= (exam.getSettings().getMaxViolations() != null ? exam.getSettings().getMaxViolations() : DEFAULT_MAX_VIOLATIONS);
                    String status = autoSubmitted ? "AUTO_SUBMITTED" : (violationCount > 0 ? "VIOLATING" : "NORMAL");

                    LocalDateTime now = TimeUtils.now();
                    boolean isGlobalExamOver = exam.getEndTime() != null && now.isAfter(exam.getEndTime());
                    boolean isStudentTimeUp = false;

                    LocalDateTime studentStartTime = null;
                    if (activeStudentIds.contains(student.getId())) {
                        studentStartTime = examSessionService.getExamStartTime(exam.getId(), student.getId()).orElse(null);
                    }

                    Integer durationInMinutes = exam.getSettings() != null ? exam.getDurationMinutes() : null;

                    if (durationInMinutes != null) {
                        if (studentStartTime != null) {
                            LocalDateTime studentEndTime = studentStartTime.plusMinutes(durationInMinutes);
                            if (now.isAfter(studentEndTime)) {
                                isStudentTimeUp = true;
                            }
                        } else if (isCurrentlyTaking && draftedStudentIds.contains(student.getId())) {
                            isStudentTimeUp = true;
                        }
                    }

                    boolean isTimeOver = isGlobalExamOver || isStudentTimeUp;

                    String examStatus = "NOT_STARTED";

                    if (isCurrentlyTaking) {
                        examStatus = isTimeOver ? (autoSubmitted ? "AUTO_SUBMITTED" : "SUBMITTED") : "IN_PROGRESS";
                    } else if (!studentResults.isEmpty()) {
                        examStatus = autoSubmitted ? "AUTO_SUBMITTED" : "SUBMITTED";
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
        int highRiskThreshold = Math.max(1, request.highRiskThreshold());
        int totalHighRisk = (int) studentResponses.stream()
                .filter(item -> item.violationCount() >= highRiskThreshold)
                .count();
        List<GetExamMonitorStudentResponse> filteredStudents = applyFilters(studentResponses, request).stream()
                .sorted((left, right) -> compareMonitorStudents(left, right, request))
                .toList();
        int safePage = Math.max(1, request.page());
        int safeSize = Math.max(1, request.size());
        int fromIndex = Math.min((safePage - 1) * safeSize, filteredStudents.size());
        int toIndex = Math.min(fromIndex + safeSize, filteredStudents.size());

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
                totalHighRisk,
                filteredStudents.size(),
                filteredStudents.subList(fromIndex, toIndex));
    }

    private List<GetExamMonitorStudentResponse> applyFilters(
            List<GetExamMonitorStudentResponse> students,
            GetExamMonitorRequest request) {
        String keyword = request.keyword() == null ? "" : request.keyword().trim().toLowerCase();
        String riskFilter = request.riskFilter() == null ? "all" : request.riskFilter();
        String examStatusFilter = request.examStatusFilter() == null ? "ALL" : request.examStatusFilter();
        int highRiskThreshold = Math.max(1, request.highRiskThreshold());

        return students.stream()
                .filter(student -> keyword.isBlank()
                        || student.studentName().toLowerCase().contains(keyword))
                .filter(student -> switch (riskFilter) {
                    case "none" -> student.violationCount() == 0;
                    case "low" -> student.violationCount() > 0 && student.violationCount() < highRiskThreshold;
                    case "high" -> student.violationCount() >= highRiskThreshold;
                    default -> true;
                })
                .filter(student -> "ALL".equalsIgnoreCase(examStatusFilter)
                        || student.examStatus().equalsIgnoreCase(examStatusFilter))
                .toList();
    }

    private int compareMonitorStudents(
            GetExamMonitorStudentResponse left,
            GetExamMonitorStudentResponse right,
            GetExamMonitorRequest request) {
        String sortColumn = request.sortColumn() == null ? "violationCount" : request.sortColumn();
        String sortDirection = request.sortDirection() == null ? "desc" : request.sortDirection();

        boolean directionApplied = "latestViolationAt".equalsIgnoreCase(sortColumn);
        int result = switch (sortColumn) {
            case "latestViolationAt" -> compareLatestViolationAt(left.latestViolationAt(), right.latestViolationAt(), sortDirection);
            case "violationCount" -> Integer.compare(left.violationCount(), right.violationCount());
            default -> 0;
        };

        if (result != 0) {
            return directionApplied || "asc".equalsIgnoreCase(sortDirection) ? result : -result;
        }

        if (left.autoSubmitted() != right.autoSubmitted()) {
            return left.autoSubmitted() ? 1 : -1;
        }
        if (left.violationCount() != right.violationCount()) {
            return Integer.compare(right.violationCount(), left.violationCount());
        }
        return Comparator.nullsLast(String::compareToIgnoreCase)
                .compare(left.studentName(), right.studentName());
    }

    private int compareLatestViolationAt(
            LocalDateTime left,
            LocalDateTime right,
            String sortDirection) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }

        int result = left.compareTo(right);
        return "asc".equalsIgnoreCase(sortDirection) ? result : -result;
    }
}
