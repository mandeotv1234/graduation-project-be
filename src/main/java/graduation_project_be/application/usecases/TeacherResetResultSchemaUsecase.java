package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.TeacherResetResultSchemaRequest;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.SpecDataset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class TeacherResetResultSchemaUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;

    public void execute(TeacherResetResultSchemaRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();

        // Validate exam exists
        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        // Validate teacher has access
        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("You do not have access to this exam");
        }

        // Load the ExamResult
        ExamResult result = examResultRepository.findById(request.resultId())
                .orElseThrow(() -> new ResourceNotFoundException("ExamResult", "id", request.resultId()));

        // Validate result belongs to this exam
        if (!result.getExamId().equals(request.examId())) {
            throw new BadRequestException("Result does not belong to this exam");
        }

        // Get schema name from submissions
        String schemaName = resolveSchemaName(result);

        // Load the exam specification
        boolean shouldLoadDdl = exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl());

        String ddlScript = null;
        String dataScript = null;

        if (shouldLoadDdl && exam.getSpecificationId() != null) {
            ExamSpecification specification = examSpecificationRepository.findById(exam.getSpecificationId())
                    .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", exam.getSpecificationId()));
            ddlScript = specification.getDdlScript();

            Long seedDatasetId = exam.getSettings().getSeedDatasetId();
            if (seedDatasetId != null && specification.getDatasets() != null) {
                dataScript = specification.getDatasets().stream()
                        .filter(SpecDataset::isActive)
                        .filter(dataset -> seedDatasetId.equals(dataset.getId()))
                        .map(SpecDataset::getDataScript)
                        .filter(script -> script != null && !script.isBlank())
                        .findFirst()
                        .orElse(null);
            }
        }

        // keepTables mirrors student ClearExamSchemaUsecase:
        // isLoadDdl=true → spec tables already exist → only wipe routines/triggers, keep tables+data
        // isLoadDdl=false → student created tables themselves → wipe everything then reload
        boolean keepTables = exam.getSettings() != null
                && Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl());
        examSchemaService.resetSchema(schemaName, keepTables);

        // Only reload DDL template when tables were actually dropped
        if (!keepTables) {
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, dataScript);
        }

        log.info("Teacher {} reset schema [{}] for result {} (exam={}, student={}, attempt={})",
                teacherId, schemaName, result.getId(),
                result.getExamId(), result.getStudentId(), result.getAttemptNumber());
    }

    private String resolveSchemaName(ExamResult result) {
        // Try to get schema name from submissions
        List<ExamSubmission> submissions = examSubmissionRepository
                .findByExamIdAndStudentIdAndAttemptNumber(
                        result.getExamId(), result.getStudentId(), result.getAttemptNumber());

        if (submissions != null && !submissions.isEmpty()) {
            String assignedSchemaName = submissions.get(0).getAssignedSchemaName();
            if (assignedSchemaName != null && !assignedSchemaName.isBlank()) {
                return assignedSchemaName;
            }
        }

        // Fallback: compute from attempt number (for old data before this feature)
        String fallback = String.format("exam_%d_student_%d_att_%d",
                result.getExamId(), result.getStudentId(), result.getAttemptNumber());
        log.warn("No assignedSchemaName found in submissions for result {}, falling back to computed name: {}",
                result.getId(), fallback);
        return fallback;
    }
}
