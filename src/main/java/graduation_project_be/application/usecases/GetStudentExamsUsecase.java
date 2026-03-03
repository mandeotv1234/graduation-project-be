package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.StudentExamListResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetStudentExamsUsecase {

    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;

    public List<StudentExamListResponse> execute() {
        Long studentId = currentUserService.getCurrentUserId();

        List<Exam> exams = examRepository.findPublishedExamsByStudentId(studentId);
        return exams.stream()
                .map(StudentExamListResponse::fromModel)
                .toList();
    }
}
