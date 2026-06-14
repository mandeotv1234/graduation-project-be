package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.response.InitializePreviewSchemaResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.TableMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class InitializePreviewSchemaUsecase {

    private static final String TEACHER_SCHEMA_FORMAT = "exam_%d_teacher_%d";

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final ExamSpecificationRepository examSpecificationRepository;

    public InitializePreviewSchemaResponse execute(Long examId) {
        Long teacherId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("Bạn không có quyền khởi tạo schema xem thử");
        }

        String schemaName = String.format(TEACHER_SCHEMA_FORMAT, examId, teacherId);
        examSchemaService.resetSchema(schemaName, false);

        boolean shouldLoadDdl = exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl());

        if (shouldLoadDdl && exam.getSpecificationId() != null) {
            ExamSpecification spec = examSpecificationRepository.findById(exam.getSpecificationId())
                    .orElse(null);
            if (spec != null && spec.getDdlScript() != null) {
                String seedScript = resolveSeedScript(spec, exam.getSettings().getSeedDatasetId());
                examSchemaService.loadTemplateIntoSchema(schemaName, spec.getDdlScript(), seedScript);
                log.info("Preview schema [{}] đã được khởi tạo với DDL + seed", schemaName);
            }
        }

        List<TableMetadata> metadata = examSchemaService.extractMetadata(schemaName);
        return InitializePreviewSchemaResponse.fromMetadata(metadata);
    }

    private String resolveSeedScript(ExamSpecification spec, Long seedDatasetId) {
        if (seedDatasetId == null || spec.getDatasets() == null) return null;
        return spec.getDatasets().stream()
                .filter(SpecDataset::isActive)
                .filter(d -> seedDatasetId.equals(d.getId()))
                .map(SpecDataset::getDataScript)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);
    }
}
