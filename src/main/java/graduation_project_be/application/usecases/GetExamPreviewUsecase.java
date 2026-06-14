package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.response.GetStudentExamResponse;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.shared.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class GetExamPreviewUsecase {

    private static final String TEACHER_SCHEMA_FORMAT = "exam_%d_teacher_%d";

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;

    public GetStudentExamResponse execute(Long examId) {
        Long teacherId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("Bạn không có quyền xem thử đề thi này");
        }

        Class clazz = classRepository.findById(exam.getClassId());
        String className = clazz != null ? clazz.getClassCode() : null;

        String schemaName = String.format(TEACHER_SCHEMA_FORMAT, examId, teacherId);
        List<TableMetadata> schema;
        try {
            schema = examSchemaService.extractMetadata(schemaName);
        } catch (Exception e) {
            log.warn("Không thể đọc schema [{}]: {}", schemaName, e.getMessage());
            schema = List.of();
        }

        return new GetStudentExamResponse(
                exam.getId(),
                exam.getClassId(),
                className,
                exam.getTitle(),
                exam.getDurationMinutes(),
                exam.getStartTime(),
                exam.getEndTime(),
                TimeUtils.now(),
                "IN_PROGRESS",
                0L,
                exam.getDescription(),
                exam.getMaxAttempts(),
                0L,
                exam.getLateThreshold(),
                exam.getSettings(),
                schema,
                exam.getPdfFilePath(),
                exam.getOriginalPdfFileName()
        );
    }
}
