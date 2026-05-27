package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.ExecuteSqlRequest;
import graduation_project_be.application.usecases.response.ExecuteSqlResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.enums.Role;
import graduation_project_be.domain.models.SqlExecutionResult;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

@RequiredArgsConstructor
public class ExecuteSqlUsecase {

    private static final String STUDENT_SCHEMA_FORMAT = "exam_%d_student_%d_att_%d";
    private static final String TEACHER_SCHEMA_FORMAT = "exam_%d_teacher_%d";

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final ExamSessionService examSessionService;
    private final ExamResultRepository examResultRepository;

    public ExecuteSqlResponse execute(ExecuteSqlRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Role currentRole = currentUserService.getCurrentUser().getRole();
        ExecutionContext context = resolveExecutionContext(request, currentUserId, currentRole);

        return executeInSchema(request.sql(), context.schemaName());
    }

    private ExecutionContext resolveExecutionContext(ExecuteSqlRequest request, Long currentUserId, Role currentRole) {
        if (currentRole == Role.STUDENT) {
            return resolveStudentExecutionContext(request, currentUserId);
        }

        if (currentRole == Role.TEACHER) {
            return resolveTeacherExecutionContext(request, currentUserId);
        }

        throw new UnauthorizedException("You do not have permission to execute SQL for this exam");
    }

    private ExecutionContext resolveStudentExecutionContext(ExecuteSqlRequest request, Long currentUserId) {
        Exam exam = examRepository.findByIdAndIsPublished(request.examId(), true)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), currentUserId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        // Validate session fingerprint
        if (!examSessionService.isSessionValid(request.examId(), currentUserId, request.ipAddress(), request.userAgent())) {
            throw new BadRequestException("Session invalid or replaced by another device. Please refresh.");
        }

        validateExamTime(request.examId(), currentUserId, exam);
        long completed = examResultRepository.countByExamIdAndStudentId(request.examId(), currentUserId);
        int currentAttempt = (int) completed + 1;
        String schemaName = String.format(STUDENT_SCHEMA_FORMAT, request.examId(), currentUserId, currentAttempt);
        return new ExecutionContext(schemaName);
    }

    private ExecutionContext resolveTeacherExecutionContext(ExecuteSqlRequest request, Long currentUserId) {
        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        if (!hasAccess) {
            throw new UnauthorizedException("You do not have access to this exam");
        }

        String schemaName = String.format(TEACHER_SCHEMA_FORMAT, request.examId(), currentUserId);

        // Authoring flow is frequently rerun with CREATE scripts.
        // Reset teacher sandbox schema to avoid "object already exists" on rerun.
        // TODO: Teacher sandbox schemas are never dropped. Add a scheduled cleanup job
        //       or drop them when the teacher saves the specification.
        if (shouldResetTeacherSchemaBeforeExecute(request.sql())) {
            examSchemaService.resetSchema(schemaName, false);
        }

        return new ExecutionContext(schemaName);
    }

    private ExecuteSqlResponse executeInSchema(String sql, String schemaName) {
        long startTime = System.currentTimeMillis();
        try {
            SqlExecutionResult result = examSchemaService.executeSql(schemaName, sql);
            List<Map<String, Object>> resultSet = result.getResultSet();
            List<TableMetadata> schema = null;
            List<RoutineMetadata> routines = null;
            if (affectsSchema(sql)) {
                schema = examSchemaService.extractMetadata(schemaName);
                routines = examSchemaService.extractRoutineMetadata(schemaName);
            }
            int executionTimeMs = (int) (System.currentTimeMillis() - startTime);
            return ExecuteSqlResponse.success(resultSet, result.getRowCount(), executionTimeMs, result.getStatusMessage(), schema, routines);
        } catch (Exception e) {
            return ExecuteSqlResponse.error(e.getMessage());
        }
    }

    private boolean affectsSchema(String sql) {
        if (sql == null) {
            return false;
        }

        // Remove comments to properly detect DDL keywords at the start
        String cleanSql = removeSqlComments(sql)
                .trim()
                .toUpperCase(Locale.ROOT);

        return cleanSql.startsWith("CREATE ")
                || cleanSql.startsWith("ALTER ")
                || cleanSql.startsWith("DROP ")
                || cleanSql.startsWith("TRUNCATE ");
    }

    private boolean shouldResetTeacherSchemaBeforeExecute(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }

        String upper = removeSqlComments(sql).toUpperCase(Locale.ROOT);
        return upper.contains("CREATE TABLE")
                || upper.contains("CREATE VIEW")
                || upper.contains("CREATE PROCEDURE")
                || upper.contains("CREATE FUNCTION")
                || upper.contains("CREATE TRIGGER");
    }

    private String removeSqlComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private void validateExamTime(Long examId, Long studentId, Exam exam) {
        Optional<LocalDateTime> startTimeOpt = examSessionService.getExamStartTime(examId, studentId);

        if (startTimeOpt.isPresent()) {
            LocalDateTime examStartedAt = startTimeOpt.get();
            LocalDateTime examDeadline = examStartedAt.plusMinutes(exam.getDurationMinutes());

            if (exam.getEndTime() != null && exam.getEndTime().isBefore(examDeadline)) {
                examDeadline = exam.getEndTime();
            }

            LocalDateTime now = TimeUtils.now();
            if (now.isAfter(examDeadline)) {
                throw new BadRequestException("Exam time has expired. You can no longer execute SQL.");
            }
        }
    }

    private record ExecutionContext(String schemaName) {
    }
}
