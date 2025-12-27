package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.GetStudentExamResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GetStudentExamUsecase {

    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;

    public GetStudentExamResponse execute(Long examId) {
        Long studentId = currentUserService.getCurrentUserId();

        // Find published exam by ID
        Exam exam = examRepository.findByIdAndIsPublished(examId, true)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // Verify student is enrolled in the class
        if (exam.getClassId() != null) {
            boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                    exam.getClassId(), studentId);
            if (!isEnrolled) {
                throw new BadRequestException("Student is not enrolled in the class for this exam");
            }
        }

        return GetStudentExamResponse.fromModel(exam);
    }
}
