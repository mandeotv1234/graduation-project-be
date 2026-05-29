package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GetStudentProgressInClassRequest;
import graduation_project_be.application.usecases.response.GetStudentProgressInClassResponse;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.GradingStatus;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetStudentProgressInClassUsecase {

    private static final String DEFAULT_GRADING_METHOD = "highest_score";
    private static final String STATUS_NOT_SUBMITTED = "NOT_SUBMITTED";

    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public GetStudentProgressInClassResponse execute(GetStudentProgressInClassRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Class clazz = classRepository.findById(request.classId());
        if (!classRepository.existsTeacherAccess(request.classId(), currentUserId)) {
            throw new UnauthorizedException("User is not the teacher of this class");
        }

        if (!classEnrollmentRepository.existsByClassIdAndStudentId(request.classId(), request.studentId())) {
            throw new UnauthorizedException("Student is not enrolled in this class");
        }

        User student = userRepository.findById(request.studentId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.studentId()));

        List<Exam> exams = examRepository.findByClassId(request.classId()).stream()
                .sorted(this::compareExamOrder)
                .toList();

        List<Long> examIds = exams.stream().map(Exam::getId).toList();
        Map<Long, List<ExamResult>> resultsByExam = examIds.isEmpty()
                ? Map.of()
                : examResultRepository.findByStudentIdAndExamIdIn(request.studentId(), examIds).stream()
                        .collect(Collectors.groupingBy(
                                ExamResult::getExamId,
                                LinkedHashMap::new,
                                Collectors.toCollection(ArrayList::new)));

        List<GetStudentProgressInClassResponse.ExamProgressItem> progressItems = exams.stream()
                .map(exam -> buildProgressItem(exam, resultsByExam.getOrDefault(exam.getId(), List.of())))
                .toList();

        return new GetStudentProgressInClassResponse(
                clazz.getId(),
                clazz.getClassCode(),
                clazz.getSemester(),
                new GetStudentProgressInClassResponse.StudentInfo(
                        student.getId(),
                        student.getFullName(),
                        student.getEmail()),
                progressItems);
    }

    private GetStudentProgressInClassResponse.ExamProgressItem buildProgressItem(
            Exam exam,
            List<ExamResult> rawResults) {
        List<ExamResult> results = rawResults.stream()
                .sorted(Comparator.comparingInt(ExamResult::getAttemptNumber))
                .toList();
        List<ExamResult> scoredResults = results.stream()
                .filter(this::hasScore)
                .toList();

        ExamResult latestResult = latestResult(results);
        String gradingMethod = resolveGradingMethod(exam);
        ExamResult selectedResult = selectResult(gradingMethod, scoredResults, latestResult);
        BigDecimal finalScore = resolveFinalScore(gradingMethod, selectedResult, scoredResults);
        BigDecimal maxScore = resolveMaxScore(gradingMethod, selectedResult, scoredResults, finalScore);
        BigDecimal finalPercent = calculatePercent(finalScore, maxScore);

        return new GetStudentProgressInClassResponse.ExamProgressItem(
                exam.getId(),
                exam.getTitle(),
                exam.getDurationMinutes(),
                gradingMethod,
                results.size(),
                latestResult != null ? latestResult.getSubmittedAt() : null,
                finalScore,
                maxScore,
                finalPercent,
                latestResult != null && latestResult.getStatus() != null
                        ? latestResult.getStatus().name()
                        : STATUS_NOT_SUBMITTED,
                selectedResult != null ? selectedResult.getId() : null);
    }

    private int compareExamOrder(Exam left, Exam right) {
        LocalDateTime leftDate = firstNonNull(left.getStartTime(), left.getCreatedAt());
        LocalDateTime rightDate = firstNonNull(right.getStartTime(), right.getCreatedAt());

        if (leftDate == null && rightDate == null) {
            return left.getTitle().compareToIgnoreCase(right.getTitle());
        }
        if (leftDate == null) {
            return 1;
        }
        if (rightDate == null) {
            return -1;
        }
        int dateCompare = leftDate.compareTo(rightDate);
        return dateCompare != 0 ? dateCompare : left.getTitle().compareToIgnoreCase(right.getTitle());
    }

    private LocalDateTime firstNonNull(LocalDateTime first, LocalDateTime second) {
        return first != null ? first : second;
    }

    private boolean hasScore(ExamResult result) {
        return result.getTotalScore() != null
                && result.getMaxScore() != null
                && result.getMaxScore().compareTo(BigDecimal.ZERO) > 0
                && result.getStatus() != GradingStatus.PENDING
                && result.getStatus() != GradingStatus.GRADING
                && result.getStatus() != GradingStatus.SYSTEM_ERROR;
    }

    private ExamResult latestResult(List<ExamResult> results) {
        return results.stream()
                .max(Comparator
                        .comparing(
                                ExamResult::getSubmittedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(ExamResult::getAttemptNumber))
                .orElse(null);
    }

    private String resolveGradingMethod(Exam exam) {
        ExamSettings settings = exam.getSettings();
        if (settings == null || settings.getGradingMethod() == null || settings.getGradingMethod().isBlank()) {
            return DEFAULT_GRADING_METHOD;
        }
        return settings.getGradingMethod();
    }

    private ExamResult selectResult(
            String gradingMethod,
            List<ExamResult> scoredResults,
            ExamResult latestResult) {
        if ("latest_score".equalsIgnoreCase(gradingMethod)) {
            return latestResult;
        }

        if ("average_score".equalsIgnoreCase(gradingMethod)) {
            return scoredResults.isEmpty() ? latestResult : latestResult(scoredResults);
        }

        return scoredResults.stream()
                .max((left, right) -> {
                    int scoreCompare = calculatePercent(left.getTotalScore(), left.getMaxScore())
                            .compareTo(calculatePercent(right.getTotalScore(), right.getMaxScore()));
                    return scoreCompare != 0
                            ? scoreCompare
                            : Integer.compare(left.getAttemptNumber(), right.getAttemptNumber());
                })
                .orElse(latestResult);
    }

    private BigDecimal resolveFinalScore(
            String gradingMethod,
            ExamResult selectedResult,
            List<ExamResult> scoredResults) {
        if (scoredResults.isEmpty()) {
            return null;
        }

        if ("average_score".equalsIgnoreCase(gradingMethod)) {
            BigDecimal total = scoredResults.stream()
                    .map(ExamResult::getTotalScore)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return total.divide(BigDecimal.valueOf(scoredResults.size()), 2, RoundingMode.HALF_UP);
        }

        if (selectedResult == null || !hasScore(selectedResult)) {
            return null;
        }

        return selectedResult.getTotalScore();
    }

    private BigDecimal resolveMaxScore(
            String gradingMethod,
            ExamResult selectedResult,
            List<ExamResult> scoredResults,
            BigDecimal finalScore) {
        if (finalScore == null) {
            return null;
        }

        if ("average_score".equalsIgnoreCase(gradingMethod)) {
            return scoredResults.get(0).getMaxScore();
        }

        return selectedResult != null ? selectedResult.getMaxScore() : null;
    }

    private BigDecimal calculatePercent(BigDecimal score, BigDecimal maxScore) {
        if (score == null || maxScore == null || maxScore.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return score.multiply(BigDecimal.valueOf(100))
                .divide(maxScore, 2, RoundingMode.HALF_UP);
    }
}
