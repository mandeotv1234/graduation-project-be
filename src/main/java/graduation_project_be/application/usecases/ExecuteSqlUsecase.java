package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.ExecuteSqlRequest;
import graduation_project_be.application.usecases.response.ExecuteSqlResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class ExecuteSqlUsecase {

    private final ExamRepository examRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;

    public ExecuteSqlResponse execute(ExecuteSqlRequest request) {
        Long studentId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findByIdAndIsPublished(request.examId(), true)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found or not published"));

        boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                exam.getClassId(), studentId);
        if (!isEnrolled) {
            throw new UnauthorizedException("Student is not enrolled in this exam's class");
        }

        String schemaName = String.format("exam_%d_student_%d", request.examId(), studentId);

        long startTime = System.currentTimeMillis();
        try {
            List<Map<String, Object>> resultSet = examSchemaService.executeSql(schemaName, request.sql());
            int executionTimeMs = (int) (System.currentTimeMillis() - startTime);
            return ExecuteSqlResponse.success(resultSet, executionTimeMs);
        } catch (Exception e) {
            return ExecuteSqlResponse.error(e.getMessage());
        }
    }
}
