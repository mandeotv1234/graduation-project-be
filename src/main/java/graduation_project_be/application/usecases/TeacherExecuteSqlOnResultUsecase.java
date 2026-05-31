package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.TeacherExecuteSqlOnResultRequest;
import graduation_project_be.application.usecases.response.ExecuteSqlResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.SqlExecutionResult;
import graduation_project_be.domain.models.TableMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class TeacherExecuteSqlOnResultUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;

    public ExecuteSqlResponse execute(TeacherExecuteSqlOnResultRequest request) {
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

        // Get schema name from first submission for this attempt
        String schemaName = resolveSchemaName(result);

        // Fail fast if schema was dropped (e.g. teacher ran "Xóa tất cả schema")
        if (!examSchemaService.schemaExists(schemaName)) {
            return ExecuteSqlResponse.error(
                    "Schema [" + schemaName + "] không tồn tại. Schema có thể đã bị xóa. Hãy dùng Reset DB để khôi phục.");
        }

        // Execute SQL on the student's schema
        long startTime = System.currentTimeMillis();
        try {
            SqlExecutionResult sqlResult = examSchemaService.executeSql(schemaName, request.sql());
            List<Map<String, Object>> resultSet = sqlResult.getResultSet();
            List<String> columns = sqlResult.getColumns() != null ? sqlResult.getColumns() : List.of();
            List<TableMetadata> schema = null;
            List<RoutineMetadata> routines = null;
            if (affectsSchema(request.sql())) {
                schema = examSchemaService.extractMetadata(schemaName);
                routines = examSchemaService.extractRoutineMetadata(schemaName);
            }
            int executionTimeMs = (int) (System.currentTimeMillis() - startTime);
            return ExecuteSqlResponse.success(resultSet, columns, sqlResult.getRowCount(), executionTimeMs,
                    sqlResult.getStatusMessage(), schema, routines);
        } catch (Exception e) {
            return ExecuteSqlResponse.error(e.getMessage());
        }
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

    private boolean affectsSchema(String sql) {
        if (sql == null) {
            return false;
        }
        String cleanSql = sql.replaceAll("(?m)--.*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .trim()
                .toUpperCase(Locale.ROOT);
        return cleanSql.startsWith("CREATE ")
                || cleanSql.startsWith("ALTER ")
                || cleanSql.startsWith("DROP ")
                || cleanSql.startsWith("TRUNCATE ");
    }
}
