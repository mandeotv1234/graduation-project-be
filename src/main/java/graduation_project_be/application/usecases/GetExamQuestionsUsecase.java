package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetExamQuestionsUsecase {

    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;

    public List<ExamQuestionResponse> execute(Long examId) {
        User currentUser = currentUserService.getCurrentUser();
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found"));

        // Role-aware authorization
        if (currentUser.getRole() == Role.TEACHER) {
            // Teacher must own the class that this exam belongs to
            boolean ownsClass = classRepository.existsByIdAndTeacherId(
                    exam.getClassId(), currentUser.getId());
            if (!ownsClass) {
                throw new UnauthorizedException("You do not have access to this exam");
            }
        } else if (currentUser.getRole() == Role.STUDENT) {
            // Student must be enrolled in the class AND exam must be published
            if (!Boolean.TRUE.equals(exam.getIsPublished())) {
                throw new IllegalArgumentException("Exam not found");
            }
            boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                    exam.getClassId(), currentUser.getId());
            if (!isEnrolled) {
                throw new UnauthorizedException("You are not enrolled in this exam's class");
            }
        } else {
            throw new UnauthorizedException("Access denied");
        }

        List<ExamQuestion> questions = examQuestionRepository.findByExamId(examId);
        return questions.stream()
                .map(ExamQuestionResponse::fromModel)
                .toList();
    }
}
