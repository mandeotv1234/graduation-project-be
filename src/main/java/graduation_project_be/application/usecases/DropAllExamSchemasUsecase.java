package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.DropAllExamSchemasRequest;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class DropAllExamSchemasUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;

    public void execute(DropAllExamSchemasRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();

        // Validate exam exists
        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        // Validate teacher has access
        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("You do not have access to this exam");
        }

        log.info("Teacher {} dropping all schemas for exam {}", teacherId, request.examId());
        examSchemaService.dropAllExamSchemas(request.examId());
        log.info("Teacher {} completed dropping all schemas for exam {}", teacherId, request.examId());
    }
}
