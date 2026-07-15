package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.usecases.request.GetExamResultsRequest;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.application.usecases.response.GetExamResultsResponse;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.GradingStatus;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetExamResultsUsecase {

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final UserRepository userRepository;

    public List<GetExamResultsResponse> execute(Long examId) {
        return execute(new GetExamResultsRequest(examId, 1, Integer.MAX_VALUE, "", "all", "all", "timeDesc")).data();
    }

    public PaginationResponse<GetExamResultsResponse> execute(GetExamResultsRequest request) {
        Long examId = request.examId();
        if (examRepository.findById(examId).isEmpty()) {
            throw new ResourceNotFoundException("Exam", "id", examId);
        }

        List<ExamResult> results = examResultRepository.findByExamId(examId);

        // Fetch all relevant students to populate names and emails
        List<Long> studentIds = results.stream().map(ExamResult::getStudentId).distinct().toList();
        Map<Long, User> studentMap = userRepository.findAllById(studentIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<GetExamResultsResponse> responses = results.stream().map(result -> {
            User student = studentMap.get(result.getStudentId());
            return new GetExamResultsResponse(
                    result.getId(),
                    result.getId(),
                    result.getStudentId(),
                    student != null ? student.getFullName() : "Unknown",
                    student != null ? student.getEmail() : "Unknown",
                    result.getAttemptNumber(),
                    result.getTotalScore(),
                    result.getMaxScore(),
                    result.getCorrectCount(),
                    result.getTotalQuestions(),
                    result.getStatus(),
                    result.getSubmittedAt()
            );
        }).toList();

        List<GetExamResultsResponse> filtered = applyFilters(responses, request);
        List<GetExamResultsResponse> selected = applyEncounterMode(filtered, request.encounterMode());
        List<List<GetExamResultsResponse>> groups = groupAndSortByStudent(selected, request.sortOrder());

        int safePage = Math.max(1, request.page());
        int safeSize = Math.max(1, request.size());
        int fromIndex = Math.min((safePage - 1) * safeSize, groups.size());
        int toIndex = Math.min(fromIndex + safeSize, groups.size());

        List<GetExamResultsResponse> pageData = groups.subList(fromIndex, toIndex)
                .stream()
                .flatMap(List::stream)
                .toList();

        return PaginationResponse.valueOf(
                pageData,
                PaginationResponse.PaginationMeta.valueOf(safePage - 1, safeSize, groups.size()));
    }

    private List<GetExamResultsResponse> applyFilters(
            List<GetExamResultsResponse> responses,
            GetExamResultsRequest request) {
        String keyword = request.keyword() == null ? "" : request.keyword().trim().toLowerCase();
        String scoreFilter = request.scoreFilter() == null ? "all" : request.scoreFilter();

        return responses.stream()
                .filter(item -> {
                    if (keyword.isBlank()) {
                        return true;
                    }
                    return item.studentName().toLowerCase().contains(keyword)
                            || item.studentEmail().toLowerCase().contains(keyword);
                })
                .filter(item -> {
                    if ("all".equalsIgnoreCase(scoreFilter)) {
                        return true;
                    }
                    if (item.status() != GradingStatus.COMPLETED && item.status() != GradingStatus.FAILED) {
                        return false;
                    }
                    BigDecimal score = item.totalScore();
                    if ("gte5".equalsIgnoreCase(scoreFilter)) {
                        return score.compareTo(BigDecimal.valueOf(5)) >= 0;
                    }
                    if ("gte8".equalsIgnoreCase(scoreFilter)) {
                        return score.compareTo(BigDecimal.valueOf(8)) >= 0;
                    }
                    if ("gte9".equalsIgnoreCase(scoreFilter)) {
                        return score.compareTo(BigDecimal.valueOf(9)) >= 0;
                    }
                    return true;
                })
                .toList();
    }

    private List<GetExamResultsResponse> applyEncounterMode(
            List<GetExamResultsResponse> responses,
            String encounterMode) {
        String mode = encounterMode == null ? "all" : encounterMode;
        if ("all".equalsIgnoreCase(mode)) {
            return responses;
        }

        Map<Long, GetExamResultsResponse> selectedByStudent = new LinkedHashMap<>();
        for (GetExamResultsResponse item : responses) {
            GetExamResultsResponse existing = selectedByStudent.get(item.studentId());
            if (existing == null) {
                selectedByStudent.put(item.studentId(), item);
                continue;
            }

            if ("latest".equalsIgnoreCase(mode) && item.attemptNumber() > existing.attemptNumber()) {
                selectedByStudent.put(item.studentId(), item);
            }

            if ("highest".equalsIgnoreCase(mode)) {
                int scoreCompare = item.totalScore().compareTo(existing.totalScore());
                if (scoreCompare > 0 || (scoreCompare == 0 && item.attemptNumber() > existing.attemptNumber())) {
                    selectedByStudent.put(item.studentId(), item);
                }
            }
        }

        return new ArrayList<>(selectedByStudent.values());
    }

    private List<List<GetExamResultsResponse>> groupAndSortByStudent(
            List<GetExamResultsResponse> responses,
            String sortOrder) {
        Map<Long, List<GetExamResultsResponse>> grouped = responses.stream()
                .collect(Collectors.groupingBy(
                        GetExamResultsResponse::studentId,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));

        List<List<GetExamResultsResponse>> groups = new ArrayList<>(grouped.values());
        groups.forEach(group -> group.sort(Comparator.comparingInt(GetExamResultsResponse::attemptNumber).reversed()));

        Comparator<List<GetExamResultsResponse>> comparator = (left, right) -> {
            GetExamResultsResponse a = left.get(0);
            GetExamResultsResponse b = right.get(0);

            String order = sortOrder == null ? "timeDesc" : sortOrder;
            return switch (order) {
                case "timeAsc" -> a.submittedAt().compareTo(b.submittedAt());
                case "scoreDesc" -> {
                    int scoreCompare = b.totalScore().compareTo(a.totalScore());
                    yield scoreCompare != 0 ? scoreCompare : b.submittedAt().compareTo(a.submittedAt());
                }
                case "scoreAsc" -> {
                    int scoreCompare = a.totalScore().compareTo(b.totalScore());
                    yield scoreCompare != 0 ? scoreCompare : b.submittedAt().compareTo(a.submittedAt());
                }
                default -> b.submittedAt().compareTo(a.submittedAt());
            };
        };

        groups.sort(comparator);
        return groups;
    }
}
