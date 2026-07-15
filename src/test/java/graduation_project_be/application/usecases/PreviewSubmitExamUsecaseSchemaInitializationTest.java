package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.grading.CreateTableQuestionGrader;
import graduation_project_be.application.usecases.grading.GradingSupport;
import graduation_project_be.application.usecases.grading.InsertDataQuestionGrader;
import graduation_project_be.application.usecases.grading.RoutineQuestionGrader;
import graduation_project_be.application.usecases.grading.SelectQuestionGrader;
import graduation_project_be.application.usecases.grading.TriggerQuestionGrader;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxEngine;
import graduation_project_be.application.usecases.request.PreviewSubmitRequest;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.ExamSpecification;
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
class PreviewSubmitExamUsecaseSchemaInitializationTest {

    private static final long EXAM_ID = 10L;
    private static final long TEACHER_ID = 50L;
    private static final long SPECIFICATION_ID = 30L;
    private static final String DDL = "CREATE TABLE Students(id INT);";
    private static final String STUDENT_CREATE_SQL = "CREATE TABLE Students(id INT);";
    private static final String PREVIEW_SCHEMA = "exam_10_teacher_50";
    private static final String REFERENCE_SCHEMA = "exam_10_teacher_50_preview_grade";

    @Mock private ExamRepository examRepository;
    @Mock private ClassRepository classRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private ExamSpecificationRepository examSpecificationRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private ExamSchemaService examSchemaService;
    @Mock private GradingSupport support;
    @Mock private CreateTableQuestionGrader createTableGrader;
    @Mock private InsertDataQuestionGrader insertDataGrader;
    @Mock private SelectQuestionGrader selectGrader;
    @Mock private RoutineQuestionGrader routineGrader;
    @Mock private TriggerQuestionGrader triggerGrader;
    @Mock private WhiteboxEngine whiteboxEngine;

    private PreviewSubmitExamUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new PreviewSubmitExamUsecase(
                examRepository,
                classRepository,
                examQuestionRepository,
                examSpecificationRepository,
                currentUserService,
                examSchemaService,
                support,
                createTableGrader,
                insertDataGrader,
                selectGrader,
                routineGrader,
                triggerGrader,
                new ObjectMapper(),
                whiteboxEngine);
    }

    @Test
    void execute_doesNotLoadDdlIntoPreviewSchemaWhenDisabled() {
        stubPreview(false, true);

        usecase.execute(new PreviewSubmitRequest(
                EXAM_ID,
                List.of(new PreviewSubmitRequest.AnswerItem(60L, STUDENT_CREATE_SQL))));

        var replayOrder = inOrder(examSchemaService);
        replayOrder.verify(examSchemaService).resetSchema(PREVIEW_SCHEMA, false);
        replayOrder.verify(examSchemaService).executeSql(PREVIEW_SCHEMA, STUDENT_CREATE_SQL);
        verify(examSchemaService, never())
                .loadTemplateIntoSchema(eq(PREVIEW_SCHEMA), any(), any());
        verify(examSchemaService).loadTemplateIntoSchema(REFERENCE_SCHEMA, DDL, null);
    }

    @Test
    void execute_loadsDdlIntoPreviewSchemaWhenEnabled() {
        stubPreview(true, false);

        usecase.execute(new PreviewSubmitRequest(EXAM_ID, List.of()));

        verify(examSchemaService).loadTemplateIntoSchema(PREVIEW_SCHEMA, DDL, null);
        verify(examSchemaService).loadTemplateIntoSchema(REFERENCE_SCHEMA, DDL, null);
    }

    private void stubPreview(boolean isLoadDdl, boolean includeCreateQuestion) {
        Exam exam = Exam.builder()
                .id(EXAM_ID)
                .classId(40L)
                .specificationId(SPECIFICATION_ID)
                .settings(ExamSettings.builder().isLoadDdl(isLoadDdl).build())
                .build();
        ExamSpecification specification = ExamSpecification.builder()
                .id(SPECIFICATION_ID)
                .ddlScript(DDL)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(TEACHER_ID);
        when(examRepository.findById(EXAM_ID)).thenReturn(Optional.of(exam));
        when(classRepository.existsTeacherAccess(40L, TEACHER_ID)).thenReturn(true);
        when(examSpecificationRepository.findById(SPECIFICATION_ID)).thenReturn(Optional.of(specification));
        if (includeCreateQuestion) {
            ExamQuestion question = ExamQuestion.builder()
                    .id(60L)
                    .examId(EXAM_ID)
                    .questionType(QuestionType.CREATE_TABLE)
                    .points(BigDecimal.ONE)
                    .orderIndex(1)
                    .build();
            when(examQuestionRepository.findByExamId(EXAM_ID)).thenReturn(List.of(question));
        } else {
            when(examQuestionRepository.findByExamId(EXAM_ID)).thenReturn(List.of());
        }
    }
}
