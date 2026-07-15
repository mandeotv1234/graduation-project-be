package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.port.services.GradingNotificationService;
import graduation_project_be.application.usecases.grading.CreateTableQuestionGrader;
import graduation_project_be.application.usecases.grading.GradingSupport;
import graduation_project_be.application.usecases.grading.InsertDataQuestionGrader;
import graduation_project_be.application.usecases.grading.RoutineQuestionGrader;
import graduation_project_be.application.usecases.grading.SelectQuestionGrader;
import graduation_project_be.application.usecases.grading.TriggerQuestionGrader;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxEngine;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxResult;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GradeExamUsecaseSchemaInitializationTest {

    private static final long EXAM_ID = 10L;
    private static final long STUDENT_ID = 20L;
    private static final int ATTEMPT_NUMBER = 1;
    private static final long SPECIFICATION_ID = 30L;
    private static final String DDL = "CREATE TABLE Students(id INT);";
    private static final String STUDENT_CREATE_SQL = "CREATE TABLE Students(id INT);";
    private static final String STUDENT_SCHEMA = "exam_10_student_20_att_1";
    private static final String TEACHER_SCHEMA = STUDENT_SCHEMA + "_teacher";

    @Mock private ExamRepository examRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private ExamSubmissionRepository examSubmissionRepository;
    @Mock private ExamResultRepository examResultRepository;
    @Mock private ExamSpecificationRepository examSpecificationRepository;
    @Mock private ClassRepository classRepository;
    @Mock private ExamSchemaService examSchemaService;
    @Mock private ExamSessionService examSessionService;
    @Mock private GradingNotificationService gradingNotificationService;
    @Mock private UserRepository userRepository;
    @Mock private GradingSupport support;
    @Mock private CreateTableQuestionGrader createTableGrader;
    @Mock private InsertDataQuestionGrader insertDataGrader;
    @Mock private SelectQuestionGrader selectGrader;
    @Mock private RoutineQuestionGrader routineGrader;
    @Mock private TriggerQuestionGrader triggerGrader;
    @Mock private WhiteboxEngine whiteboxEngine;

    private GradeExamUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new GradeExamUsecase(
                examRepository,
                examQuestionRepository,
                examSubmissionRepository,
                examResultRepository,
                examSpecificationRepository,
                classRepository,
                examSchemaService,
                examSessionService,
                gradingNotificationService,
                userRepository,
                new ObjectMapper(),
                support,
                createTableGrader,
                insertDataGrader,
                selectGrader,
                routineGrader,
                triggerGrader,
                whiteboxEngine);
    }

    @Test
    void execute_doesNotLoadDdlIntoStudentSchemaWhenDisabled() {
        stubGrading(false, true);

        usecase.execute(EXAM_ID, STUDENT_ID, ATTEMPT_NUMBER);

        var replayOrder = inOrder(examSchemaService);
        replayOrder.verify(examSchemaService).resetSchema(STUDENT_SCHEMA, false);
        replayOrder.verify(examSchemaService).executeSql(STUDENT_SCHEMA, STUDENT_CREATE_SQL);
        verify(examSchemaService, never())
                .loadTemplateIntoSchema(eq(STUDENT_SCHEMA), any(), any());
        verify(examSchemaService).loadTemplateIntoSchema(TEACHER_SCHEMA, DDL, null);
    }

    @Test
    void execute_loadsDdlIntoStudentSchemaWhenEnabled() {
        stubGrading(true, false);

        usecase.execute(EXAM_ID, STUDENT_ID, ATTEMPT_NUMBER);

        verify(examSchemaService).loadTemplateIntoSchema(STUDENT_SCHEMA, DDL, null);
        verify(examSchemaService).loadTemplateIntoSchema(TEACHER_SCHEMA, DDL, null);
    }

    private void stubGrading(boolean isLoadDdl, boolean includeCreateSubmission) {
        Exam exam = Exam.builder()
                .id(EXAM_ID)
                .classId(40L)
                .specificationId(SPECIFICATION_ID)
                .title("Database exam")
                .settings(ExamSettings.builder().isLoadDdl(isLoadDdl).build())
                .build();
        ExamResult result = ExamResult.builder()
                .examId(EXAM_ID)
                .studentId(STUDENT_ID)
                .attemptNumber(ATTEMPT_NUMBER)
                .build();
        ExamSpecification specification = ExamSpecification.builder()
                .id(SPECIFICATION_ID)
                .ddlScript(DDL)
                .build();

        when(examRepository.findByIdAndIsPublished(EXAM_ID, true)).thenReturn(Optional.of(exam));
        when(examResultRepository.findByExamIdAndStudentIdAndAttemptNumber(
                EXAM_ID, STUDENT_ID, ATTEMPT_NUMBER)).thenReturn(Optional.of(result));
        when(examSpecificationRepository.findById(SPECIFICATION_ID)).thenReturn(Optional.of(specification));
        if (includeCreateSubmission) {
            ExamQuestion question = ExamQuestion.builder()
                    .id(60L)
                    .examId(EXAM_ID)
                    .questionType(QuestionType.CREATE_TABLE)
                    .points(BigDecimal.ONE)
                    .orderIndex(1)
                    .build();
            ExamSubmission submission = ExamSubmission.builder()
                    .id(70L)
                    .examId(EXAM_ID)
                    .questionId(question.getId())
                    .studentId(STUDENT_ID)
                    .attemptNumber(ATTEMPT_NUMBER)
                    .studentQuery(STUDENT_CREATE_SQL)
                    .build();
            when(examQuestionRepository.findByExamId(EXAM_ID)).thenReturn(List.of(question));
            when(examSubmissionRepository.findByExamIdAndStudentIdAndAttemptNumber(
                    EXAM_ID, STUDENT_ID, ATTEMPT_NUMBER)).thenReturn(List.of(submission));
            when(whiteboxEngine.evaluateFromPayload(
                    eq("CREATE_TABLE"), eq(STUDENT_CREATE_SQL), any(), eq(BigDecimal.ONE), eq(true)))
                    .thenReturn(WhiteboxResult.empty());
        } else {
            when(examQuestionRepository.findByExamId(EXAM_ID)).thenReturn(List.of());
            when(examSubmissionRepository.findByExamIdAndStudentIdAndAttemptNumber(
                    EXAM_ID, STUDENT_ID, ATTEMPT_NUMBER)).thenReturn(List.of());
        }
        when(classRepository.findTeachersByClassId(40L)).thenReturn(List.of());
        when(userRepository.findById(STUDENT_ID)).thenReturn(Optional.empty());
    }
}
