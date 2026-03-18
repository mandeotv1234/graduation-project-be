package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.ExecuteSqlRequest;
import graduation_project_be.application.usecases.response.ExecuteSqlResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.TableMetadata;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

@RequiredArgsConstructor
public class ExecuteSqlUsecase {

    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final ExamSessionService examSessionService;

    public ExecuteSqlResponse execute(ExecuteSqlRequest request) {
        Long studentId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findByIdAndIsPublished(request.examId(), true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), studentId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        // Backend time validation — prevent executing SQL after time expires
        validateExamTime(request.examId(), studentId, exam);

        String schemaName = String.format("exam_%d_student_%d", request.examId(), studentId);

        long startTime = System.currentTimeMillis();
        try {
            List<Map<String, Object>> resultSet = examSchemaService.executeSql(schemaName, request.sql());
            List<TableMetadata> schema = null;
            if (affectsSchema(request.sql())) {
                schema = examSchemaService.extractMetadata(schemaName);
            }
            int executionTimeMs = (int) (System.currentTimeMillis() - startTime);
            return ExecuteSqlResponse.success(resultSet, executionTimeMs, schema);
        } catch (Exception e) {
            return ExecuteSqlResponse.error(e.getMessage());
        }
    }

    private boolean affectsSchema(String sql) {
        if (sql == null) return false;
        String upper = sql.trim().toUpperCase(Locale.ROOT);
        // Most common DDL statements students write in this system
        return upper.startsWith("CREATE ")
                || upper.startsWith("ALTER ")
                || upper.startsWith("DROP ")
                || upper.startsWith("TRUNCATE ");
    }

    private void validateExamTime(Long examId, Long studentId, Exam exam) {
        Optional<LocalDateTime> startTimeOpt = examSessionService.getExamStartTime(examId, studentId);

        if (startTimeOpt.isPresent()) {
            LocalDateTime examStartedAt = startTimeOpt.get();
            LocalDateTime examDeadline = examStartedAt.plusMinutes(exam.getDurationMinutes());

            if (exam.getEndTime() != null && exam.getEndTime().isBefore(examDeadline)) {
                examDeadline = exam.getEndTime();
            }

            LocalDateTime now = LocalDateTime.now();
            if (now.isAfter(examDeadline)) {
                throw new BadRequestException("Exam time has expired. You can no longer execute SQL.");
            }
        }
    }
}
