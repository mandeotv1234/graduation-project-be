package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.PrepareExamSchemasRequest;
import graduation_project_be.application.usecases.response.PrepareExamSchemasResponse;
import graduation_project_be.application.usecases.response.PrepareExamSchemasResponse.PreparationStatus;
import graduation_project_be.application.usecases.response.PrepareExamSchemasResponse.StudentSchemaPreparationResult;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class PrepareExamSchemasUsecase {

    private static final String STUDENT_SCHEMA_FORMAT = "exam_%d_student_%d_att_%d";

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final ExamSessionService examSessionService;

    public PrepareExamSchemasResponse execute(PrepareExamSchemasRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();
        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        if (!classRepository.existsTeacherAccess(exam.getClassId(), teacherId)) {
            throw new UnauthorizedException("You do not have access to this exam");
        }

        boolean shouldLoadTemplate = shouldInitializeDatabase(exam);
        SchemaTemplate template = resolveTemplate(exam, shouldLoadTemplate);
        List<ClassEnrollment> enrollments = classEnrollmentRepository.findByClassId(exam.getClassId());
        List<StudentSchemaPreparationResult> results = new ArrayList<>();

        for (ClassEnrollment enrollment : enrollments) {
            results.add(prepareStudentSchema(exam, enrollment.getStudentId(), request.forceRebuild(),
                    shouldLoadTemplate, template));
        }

        int preparedCount = count(results, PreparationStatus.PREPARED);
        int skippedReadyCount = count(results, PreparationStatus.SKIPPED_READY);
        int skippedActiveSessionCount = count(results, PreparationStatus.SKIPPED_ACTIVE_SESSION);
        int skippedMaxAttemptsCount = count(results, PreparationStatus.SKIPPED_MAX_ATTEMPTS);
        int failedCount = count(results, PreparationStatus.FAILED);

        log.info(
                "Prepared exam schemas: exam={}, teacher={}, enrolled={}, prepared={}, ready={}, active={}, maxAttempts={}, failed={}, forceRebuild={}",
                exam.getId(), teacherId, enrollments.size(), preparedCount, skippedReadyCount,
                skippedActiveSessionCount, skippedMaxAttemptsCount, failedCount, request.forceRebuild());

        return new PrepareExamSchemasResponse(
                exam.getId(),
                enrollments.size(),
                preparedCount,
                skippedReadyCount,
                skippedActiveSessionCount,
                skippedMaxAttemptsCount,
                failedCount,
                request.forceRebuild(),
                shouldLoadTemplate,
                results);
    }

    private StudentSchemaPreparationResult prepareStudentSchema(
            Exam exam,
            Long studentId,
            boolean forceRebuild,
            boolean shouldLoadTemplate,
            SchemaTemplate template) {
        if (examSessionService.getActiveSession(exam.getId(), studentId).isPresent()
                || examSessionService.getExamStartTime(exam.getId(), studentId).isPresent()) {
            return skipped(studentId, null, null, PreparationStatus.SKIPPED_ACTIVE_SESSION,
                    "Sinh viên đang có phiên thi, không reset schema.");
        }

        long completedAttempts = examResultRepository.countByExamIdAndStudentId(exam.getId(), studentId);
        if (exam.getMaxAttempts() != null && exam.getMaxAttempts() > 0
                && completedAttempts >= exam.getMaxAttempts()) {
            return skipped(studentId, null, null, PreparationStatus.SKIPPED_MAX_ATTEMPTS,
                    "Sinh viên đã hết số lần làm bài.");
        }

        int nextAttempt = (int) completedAttempts + 1;
        String schemaName = String.format(STUDENT_SCHEMA_FORMAT, exam.getId(), studentId, nextAttempt);

        try {
            if (!forceRebuild && isSchemaReady(schemaName, shouldLoadTemplate)) {
                return skipped(studentId, nextAttempt, schemaName, PreparationStatus.SKIPPED_READY,
                        "Schema đã được chuẩn bị.");
            }

            examSchemaService.resetSchema(schemaName, false);
            if (shouldLoadTemplate) {
                examSchemaService.loadTemplateIntoSchema(schemaName, template.ddlScript(), template.defaultDataScript());
            }

            return new StudentSchemaPreparationResult(
                    studentId,
                    nextAttempt,
                    schemaName,
                    PreparationStatus.PREPARED,
                    shouldLoadTemplate ? "Đã tạo schema và nạp DDL/dataset." : "Đã tạo schema trống.");
        } catch (Exception e) {
            log.warn("Failed to prepare schema for exam={}, student={}, schema={}: {}",
                    exam.getId(), studentId, schemaName, e.getMessage());
            return new StudentSchemaPreparationResult(
                    studentId,
                    nextAttempt,
                    schemaName,
                    PreparationStatus.FAILED,
                    rootMessage(e));
        }
    }

    private StudentSchemaPreparationResult skipped(
            Long studentId,
            Integer attemptNumber,
            String schemaName,
            PreparationStatus status,
            String message) {
        return new StudentSchemaPreparationResult(studentId, attemptNumber, schemaName, status, message);
    }

    private boolean isSchemaReady(String schemaName, boolean shouldLoadTemplate) {
        if (shouldLoadTemplate) {
            return !examSchemaService.extractMetadata(schemaName).isEmpty();
        }
        return examSchemaService.schemaExists(schemaName);
    }

    private SchemaTemplate resolveTemplate(Exam exam, boolean shouldLoadTemplate) {
        if (!shouldLoadTemplate) {
            return new SchemaTemplate(null, null);
        }

        Long specificationId = exam.getSpecificationId();
        if (specificationId == null) {
            throw new BadRequestException("Đề thi yêu cầu nạp DDL/dataset nhưng chưa chọn đặc tả CSDL.");
        }

        ExamSpecification specification = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", specificationId));

        String ddlScript = specification.getDdlScript();
        if (ddlScript == null || ddlScript.isBlank()) {
            throw new BadRequestException("Đặc tả CSDL đang chọn chưa có DDL script.");
        }

        return new SchemaTemplate(ddlScript, resolveSeedDatasetScript(exam, specification));
    }

    private boolean shouldInitializeDatabase(Exam exam) {
        return exam.getSettings() != null && Boolean.TRUE.equals(exam.getSettings().getIsLoadDdl());
    }

    private String resolveSeedDatasetScript(Exam exam, ExamSpecification specification) {
        Long seedDatasetId = exam.getSettings().getSeedDatasetId();
        if (seedDatasetId == null) {
            throw new BadRequestException("Đề thi yêu cầu nạp dataset nhưng chưa có seedDatasetId.");
        }

        if (specification.getDatasets() == null) {
            throw new BadRequestException("Exam requires a seed dataset, but the specification has no datasets.");
        }

        return specification.getDatasets().stream()
                .filter(SpecDataset::isActive)
                .filter(dataset -> seedDatasetId.equals(dataset.getId()))
                .map(SpecDataset::getDataScript)
                .filter(script -> script != null && !script.isBlank())
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Dataset dùng để nạp dữ liệu mẫu không tồn tại, đang tắt, hoặc không có data script."));
    }

    private int count(List<StudentSchemaPreparationResult> results, PreparationStatus status) {
        return (int) results.stream()
                .filter(result -> result.status() == status)
                .count();
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() != null ? cursor.getMessage() : throwable.getMessage();
    }

    private record SchemaTemplate(String ddlScript, String defaultDataScript) {
    }
}
