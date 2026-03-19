package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.CreateExamResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetExamsByClassUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public List<CreateExamResponse> execute(Long classId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        boolean isTeacher = classRepository.existsTeacherAccess(classId, currentUserId);
        if (!isTeacher) {
            throw new UnauthorizedException("User is not the teacher of this class");
        }

        List<Exam> exams = examRepository.findByClassId(classId);
        return exams.stream()
                .map(CreateExamResponse::fromModel)
                .toList();
    }
}
