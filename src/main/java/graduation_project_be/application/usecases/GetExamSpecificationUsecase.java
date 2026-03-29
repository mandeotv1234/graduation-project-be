package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetExamSpecificationUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;

    public ExamSpecificationResponse execute(Long examId) {
        User currentUser = currentUserService.getCurrentUser();
        Long currentUserId = currentUser.getId();
        Role currentRole = currentUser.getRole();

        // Verify exam exists
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // TEACHER: phải có quyền access class của exam
        // STUDENT: phải được enroll vào class của exam
        if (currentRole == Role.TEACHER) {
            boolean hasAccess = currentUserId.equals(exam.getCreatorId())
                    || classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
            if (!hasAccess) {
                throw new UnauthorizedException("You do not have access to this exam");
            }
        } else if (currentRole == Role.STUDENT) {
            boolean enrolled = classEnrollmentRepository
                    .existsByClassIdAndStudentId(exam.getClassId(), currentUserId);
            if (!enrolled) {
                throw new UnauthorizedException("You are not enrolled in this exam's class");
            }
        } else {
            throw new UnauthorizedException("Access denied");
        }

        if (exam.getSpecificationId() == null) {
            throw new ResourceNotFoundException("Exam", "specificationId", "null");
        }

        ExamSpecification specification = examSpecificationRepository.findById(exam.getSpecificationId())
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", exam.getSpecificationId()));

        if (currentRole == Role.STUDENT) {
            List<SpecDataset> visibleDatasets = (specification.getDatasets() == null ? List.<SpecDataset>of()
                    : specification.getDatasets().stream()
                    .filter(SpecDataset::isVisibleToStudent)
                    .toList());

            specification = ExamSpecification.builder()
                    .id(specification.getId())
                    .name(specification.getName())
                    .ddlScript(specification.isDdlVisibleToStudent() ? specification.getDdlScript() : null)
                    .ddlVisibleToStudent(specification.isDdlVisibleToStudent())
                    .schemaDiagram(specification.isSchemaDiagramVisibleToStudent()
                            ? specification.getSchemaDiagram()
                            : null)
                    .schemaDiagramVisibleToStudent(specification.isSchemaDiagramVisibleToStudent())
                    .description(specification.getDescription())
                    .entities(specification.getEntities())
                    .datasets(visibleDatasets)
                    .createdBy(specification.getCreatedBy())
                    .createdAt(specification.getCreatedAt())
                    .updatedAt(specification.getUpdatedAt())
                    .build();
        }

        return ExamSpecificationResponse.fromModel(specification);
    }
}
