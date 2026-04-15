package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GetStudentResultsRequest;
import graduation_project_be.application.usecases.response.StudentExamResultResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetStudentResultsUsecase {

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final CurrentUserService currentUserService;

    public PaginatedResult<StudentExamResultResponse> execute(GetStudentResultsRequest request) {
        Long studentId = currentUserService.getCurrentUserId();
        PaginationParams params = request.getPaginationParams();
        
        PaginatedResult<ExamResult> paginatedResults = examResultRepository.findPaginatedByStudentId(studentId, params);

        List<Long> examIds = paginatedResults.getData().stream()
                .map(ExamResult::getExamId)
                .distinct()
                .toList();

        Map<Long, Exam> examsMap = examIds.stream()
                .map(examRepository::findById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(Collectors.toMap(Exam::getId, e -> e));

        List<StudentExamResultResponse> responses = paginatedResults.getData().stream()
                .map(result -> {
                    Exam exam = examsMap.get(result.getExamId());
                    String examTitle = exam != null ? exam.getTitle() : "Unknown Exam";
                    Boolean allowReview = exam != null && exam.getSettings() != null && Boolean.TRUE.equals(exam.getSettings().getAllowReview());
                    
                    return StudentExamResultResponse.builder()
                            .id(result.getId())
                            .examId(result.getExamId())
                            .examTitle(examTitle)
                            .attemptNumber(result.getAttemptNumber())
                            .totalScore(result.getTotalScore())
                            .maxScore(result.getMaxScore())
                            .submittedAt(result.getSubmittedAt())
                            .status(result.getStatus())
                            .allowReview(allowReview)
                            .build();
                })
                .toList();

        return PaginatedResult.<StudentExamResultResponse>builder()
                .data(responses)
                .pagination(paginatedResults.getPagination())
                .build();
    }
}
