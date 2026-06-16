package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.response.GetStudentExamResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.application.usecases.request.GetStudentExamDetailRequest;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetStudentExamUsecase {
    private static final String STUDENT_SCHEMA_FORMAT = "exam_%d_student_%d_att_%d";

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final ExamResultRepository examResultRepository;

    public GetStudentExamResponse execute(GetStudentExamDetailRequest request) {
        Long studentId = currentUserService.getCurrentUserId();
        Long examId = request.examId();

        // Find published exam by ID
        Exam exam = examRepository.findByIdAndIsPublished(examId, true)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        String className = null;
        // Verify student is enrolled in the class
        if (exam.getClassId() != null) {
            Class clazz = classRepository.findById(exam.getClassId());
            className = clazz.getClassCode();
            boolean isEnrolled = classEnrollmentRepository.existsByClassIdAndStudentId(
                    exam.getClassId(), studentId);
            if (!isEnrolled) {
                throw new BadRequestException("Student is not enrolled in the class for this exam");
            }
        }

        Long usedAttempts = examResultRepository.countByExamIdAndStudentId(examId, studentId);
        int currentAttempt = usedAttempts.intValue() + 1;
        String schemaName = String.format(STUDENT_SCHEMA_FORMAT, examId, studentId, currentAttempt);
        List<TableMetadata> schema = examSchemaService.extractMetadata(schemaName);

        return GetStudentExamResponse.fromModel(exam, className, schema, usedAttempts);
    }
}
