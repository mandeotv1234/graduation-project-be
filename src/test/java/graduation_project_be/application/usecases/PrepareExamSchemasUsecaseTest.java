package graduation_project_be.application.usecases;

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
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrepareExamSchemasUsecaseTest {

    private static final Long EXAM_ID = 100L;
    private static final Long CLASS_ID = 10L;
    private static final Long TEACHER_ID = 5L;
    private static final Long SPECIFICATION_ID = 7L;
    private static final Long DATASET_ID = 9L;

    @Mock private ExamRepository examRepository;
    @Mock private ClassRepository classRepository;
    @Mock private ClassEnrollmentRepository classEnrollmentRepository;
    @Mock private ExamResultRepository examResultRepository;
    @Mock private ExamSpecificationRepository examSpecificationRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private ExamSchemaService examSchemaService;
    @Mock private ExamSessionService examSessionService;

    private PrepareExamSchemasUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new PrepareExamSchemasUsecase(
                examRepository,
                classRepository,
                classEnrollmentRepository,
                examResultRepository,
                examSpecificationRepository,
                currentUserService,
                examSchemaService,
                examSessionService);
    }

    @Test
    void execute_preparesAttemptAwareSchemasWithTemplate() {
        Exam exam = exam(true, 3);
        ExamSpecification specification = specification();

        when(currentUserService.getCurrentUserId()).thenReturn(TEACHER_ID);
        when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
        when(classRepository.existsTeacherAccess(CLASS_ID, TEACHER_ID)).thenReturn(true);
        when(examSpecificationRepository.findById(SPECIFICATION_ID)).thenReturn(Optional.of(specification));
        when(classEnrollmentRepository.findByClassId(CLASS_ID)).thenReturn(List.of(
                enrollment(1L),
                enrollment(2L)));
        when(examResultRepository.countByExamIdAndStudentId(EXAM_ID, 1L)).thenReturn(0L);
        when(examResultRepository.countByExamIdAndStudentId(EXAM_ID, 2L)).thenReturn(1L);
        when(examSessionService.getActiveSession(EXAM_ID, 1L)).thenReturn(Optional.empty());
        when(examSessionService.getActiveSession(EXAM_ID, 2L)).thenReturn(Optional.empty());
        when(examSessionService.getExamStartTime(EXAM_ID, 1L)).thenReturn(Optional.empty());
        when(examSessionService.getExamStartTime(EXAM_ID, 2L)).thenReturn(Optional.empty());
        when(examSchemaService.extractMetadata("exam_100_student_1_att_1")).thenReturn(List.of());
        when(examSchemaService.extractMetadata("exam_100_student_2_att_2")).thenReturn(List.of());

        PrepareExamSchemasResponse response = usecase.execute(new PrepareExamSchemasRequest(EXAM_ID, false));

        assertThat(response.enrolledCount()).isEqualTo(2);
        assertThat(response.preparedCount()).isEqualTo(2);
        assertThat(response.failedCount()).isZero();
        assertThat(response.results())
                .extracting(PrepareExamSchemasResponse.StudentSchemaPreparationResult::status)
                .containsOnly(PreparationStatus.PREPARED);

        verify(examSchemaService).resetSchema("exam_100_student_1_att_1", false);
        verify(examSchemaService).loadTemplateIntoSchema("exam_100_student_1_att_1",
                "CREATE TABLE Students(id INT);", "INSERT INTO Students VALUES (1);");
        verify(examSchemaService).resetSchema("exam_100_student_2_att_2", false);
        verify(examSchemaService).loadTemplateIntoSchema("exam_100_student_2_att_2",
                "CREATE TABLE Students(id INT);", "INSERT INTO Students VALUES (1);");
    }

    @Test
    void execute_skipsReadyAndActiveSchemas() {
        Exam exam = exam(true, 3);

        when(currentUserService.getCurrentUserId()).thenReturn(TEACHER_ID);
        when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
        when(classRepository.existsTeacherAccess(CLASS_ID, TEACHER_ID)).thenReturn(true);
        when(examSpecificationRepository.findById(SPECIFICATION_ID)).thenReturn(Optional.of(specification()));
        when(classEnrollmentRepository.findByClassId(CLASS_ID)).thenReturn(List.of(
                enrollment(1L),
                enrollment(2L)));
        when(examSessionService.getActiveSession(EXAM_ID, 1L)).thenReturn(Optional.empty());
        when(examSessionService.getActiveSession(EXAM_ID, 2L)).thenReturn(Optional.of("ip|ua"));
        when(examSessionService.getExamStartTime(EXAM_ID, 1L)).thenReturn(Optional.empty());
        when(examResultRepository.countByExamIdAndStudentId(EXAM_ID, 1L)).thenReturn(0L);
        when(examSchemaService.extractMetadata("exam_100_student_1_att_1")).thenReturn(List.of(
                TableMetadata.builder()
                        .tableName("Students")
                        .build()));

        PrepareExamSchemasResponse response = usecase.execute(new PrepareExamSchemasRequest(EXAM_ID, false));

        assertThat(response.preparedCount()).isZero();
        assertThat(response.skippedReadyCount()).isEqualTo(1);
        assertThat(response.skippedActiveSessionCount()).isEqualTo(1);
    }

    private Exam exam(boolean loadDdl, int maxAttempts) {
        return Exam.builder()
                .id(EXAM_ID)
                .classId(CLASS_ID)
                .specificationId(SPECIFICATION_ID)
                .maxAttempts(maxAttempts)
                .settings(ExamSettings.builder()
                        .isLoadDdl(loadDdl)
                        .seedDatasetId(DATASET_ID)
                        .build())
                .build();
    }

    private ExamSpecification specification() {
        return ExamSpecification.builder()
                .id(SPECIFICATION_ID)
                .ddlScript("CREATE TABLE Students(id INT);")
                .datasets(List.of(SpecDataset.builder()
                        .id(DATASET_ID)
                        .isActive(true)
                        .dataScript("INSERT INTO Students VALUES (1);")
                        .build()))
                .build();
    }

    private ClassEnrollment enrollment(Long studentId) {
        return ClassEnrollment.builder()
                .classId(CLASS_ID)
                .studentId(studentId)
                .build();
    }
}
