package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.StudentExamListResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class GetStudentExamsUsecase {

    private final ExamRepository examRepository;
    private final ClassStudentBanRepository classStudentBanRepository;
    private final CurrentUserService currentUserService;

    public List<StudentExamListResponse> execute() {
        Long studentId = currentUserService.getCurrentUserId();

        List<Exam> exams = examRepository.findPublishedExamsByStudentId(studentId);
        Set<Long> bannedClassIds = new HashSet<>(
                classStudentBanRepository.findActiveBannedClassIdsByStudentId(studentId));
        return exams.stream()
                .map(exam -> StudentExamListResponse.fromModel(
                        exam, bannedClassIds.contains(exam.getClassId())))
                .toList();
    }
}
