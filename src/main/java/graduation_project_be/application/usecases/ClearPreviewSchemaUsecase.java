package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ClearPreviewSchemaUsecase {

    private static final String TEACHER_SCHEMA_FORMAT = "exam_%d_teacher_%d";

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;

    public void execute(Long examId) {
        Long teacherId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("Bạn không có quyền xóa schema xem thử");
        }

        String schemaName = String.format(TEACHER_SCHEMA_FORMAT, examId, teacherId);
        examSchemaService.resetSchema(schemaName, false);
        log.info("Preview schema [{}] đã được xóa sạch", schemaName);
    }
}
