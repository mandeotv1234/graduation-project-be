package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.CreateExamRequest;
import graduation_project_be.application.usecases.response.CreateExamResponse;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
public class CreateExamUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final ExamSchemaService examSchemaService;

    @Transactional
    public CreateExamResponse execute(CreateExamRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        boolean isTeacherOfClass = classRepository.existsTeacherAccess(request.classId(), currentUserId);
        if (!isTeacherOfClass) {
            throw new UnauthorizedException("User is not the teacher of this class");
        }

        Long specificationId = request.specificationId();
        if (specificationId != null && specificationId <= 0L) {
            specificationId = null;
        }

        boolean hasPdf = request.pdfFilePath() != null && !request.pdfFilePath().isBlank();
        if (hasPdf) {
            specificationId = null;
        }

        Exam exam = Exam.builder()
                .specificationId(hasPdf ? null : specificationId)
                .classId(request.classId())
                .creatorId(currentUserId)
                .title(request.title())
                .durationMinutes(request.durationMinutes())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .isPublished(request.isPublished() != null ? request.isPublished() : false)
                .createdAt(LocalDateTime.now())
                .description(request.description())
                .maxAttempts(request.maxAttempts() != null ? request.maxAttempts() : 1)
                .lateThreshold(request.lateThreshold() != null ? request.lateThreshold() : 0)
                .settings(request.settings())
                .pdfFilePath(hasPdf ? request.pdfFilePath() : null)
                .originalPdfFileName(hasPdf ? request.originalPdfFileName() : null)
                .build();

        Exam savedExam = examRepository.save(exam);

        // Fetch Students in Class
        List<ClassEnrollment> enrollments = classEnrollmentRepository.findByClassId(request.classId());

        // Create Schema for each Student
        for (ClassEnrollment enrollment : enrollments) {
            examSchemaService.createExamSchemaForStudent(
                    savedExam.getId(),
                    enrollment.getStudentId());
        }

        return CreateExamResponse.fromModel(savedExam);
    }
}
