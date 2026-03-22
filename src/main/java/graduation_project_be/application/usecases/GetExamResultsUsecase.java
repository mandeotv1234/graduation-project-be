package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.usecases.response.GetExamResultsResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetExamResultsUsecase {

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final UserRepository userRepository;

    public List<GetExamResultsResponse> execute(Long examId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        List<ExamResult> results = examResultRepository.findByExamId(examId);

        // Fetch all relevant students to populate names and emails
        List<Long> studentIds = results.stream().map(ExamResult::getStudentId).distinct().toList();
        Map<Long, User> studentMap = userRepository.findAllById(studentIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return results.stream().map(result -> {
            User student = studentMap.get(result.getStudentId());
            return new GetExamResultsResponse(
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
    }
}
