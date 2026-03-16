package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSpecification;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GetExamSpecificationUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;

    public ExamSpecificationResponse execute(Long examId) {
        Long currentUserId = currentUserService.getCurrentUserId();
        String currentRole = currentUserService.getCurrentUser().getRole().name();

        // Verify exam exists
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // TEACHER: phải là creator của exam
        // STUDENT: phải được enroll vào class của exam
        if ("TEACHER".equals(currentRole)) {
            if (!exam.getCreatorId().equals(currentUserId)) {
                throw new graduation_project_be.application.exceptions.UnauthorizedException(
                        "You are not the creator of this exam");
            }
        } else if ("STUDENT".equals(currentRole)) {
            boolean enrolled = classEnrollmentRepository
                    .existsByClassIdAndStudentId(exam.getClassId(), currentUserId);
            if (!enrolled) {
                throw new graduation_project_be.application.exceptions.UnauthorizedException(
                        "You are not enrolled in this exam's class");
            }
        }

        if (exam.getSpecificationId() == null) {
            throw new ResourceNotFoundException("Exam", "specificationId", "null");
        }

        ExamSpecification specification = examSpecificationRepository.findById(exam.getSpecificationId())
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", exam.getSpecificationId()));

        return ExamSpecificationResponse.fromModel(specification);
    }
}
