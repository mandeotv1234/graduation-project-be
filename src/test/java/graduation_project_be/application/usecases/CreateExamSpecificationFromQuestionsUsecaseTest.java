package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.PdfStorageService;
import graduation_project_be.application.port.services.PdfTextExtractor;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.application.usecases.support.SpecificationSchemaJsonBuilder;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateExamSpecificationFromQuestionsUsecaseTest {

    @Mock private ClassRepository classRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private ExamRepository examRepository;
    @Mock private ExamSpecificationRepository examSpecificationRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private ExamSchemaService examSchemaService;
    @Mock private PdfStorageService pdfStorageService;
    @Mock private PdfTextExtractor pdfTextExtractor;

    private CreateExamSpecificationFromQuestionsUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new CreateExamSpecificationFromQuestionsUsecase(
                classRepository,
                examQuestionRepository,
                examRepository,
                examSpecificationRepository,
                currentUserService,
                examSchemaService,
                pdfStorageService,
                pdfTextExtractor,
                new SpecificationSchemaJsonBuilder(new ObjectMapper()));
    }

    @Test
    void executeCreatesAndAttachesSpecificationFromCreateAndInsertAnswers() {
        String ddl = "CREATE TABLE LEHOI (MaLeHoi CHAR(4) PRIMARY KEY)";
        String dml = "INSERT INTO LEHOI (MaLeHoi) VALUES ('LH01')";
        Exam exam = Exam.builder()
                .id(2L)
                .classId(3L)
                .title("Đề lễ hội")
                .pdfFilePath("uploads/de-bai.pdf")
                .originalPdfFileName("đề bài.pdf")
                .build();
        TableMetadata.ColumnMetadata column = TableMetadata.ColumnMetadata.builder()
                .columnName("MaLeHoi")
                .rawDataType("CHAR(4)")
                .isPrimaryKey(true)
                .isNullable(false)
                .build();
        TableMetadata table = TableMetadata.builder()
                .tableName("LEHOI")
                .columns(List.of(column))
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(11L);
        when(examRepository.findById(2L)).thenReturn(Optional.of(exam));
        when(classRepository.existsTeacherAccess(3L, 11L)).thenReturn(true);
        when(examQuestionRepository.findByExamId(2L)).thenReturn(List.of(
                question(1, QuestionType.CREATE_TABLE, ddl),
                question(2, QuestionType.INSERT_DATA, dml)));
        when(examSchemaService.extractMetadata(any(String.class))).thenReturn(List.of(table));
        when(pdfStorageService.loadPdf("uploads/de-bai.pdf")).thenReturn(new byte[] {1});
        when(pdfTextExtractor.extract(any(byte[].class)))
                .thenReturn("LEHOI Lễ hội\n\nMaLeHoi\n\nMã lễ hội");
        when(examSpecificationRepository.save(any(ExamSpecification.class)))
                .thenAnswer(invocation -> {
                    ExamSpecification specification = invocation.getArgument(0);
                    specification.setId(21L);
                    return specification;
                });

        ExamSpecificationResponse response = usecase.execute(2L);

        assertThat(response.name()).isEqualTo("Đặc tả CSDL - Đề lễ hội");
        assertThat(response.ddlScript()).isEqualTo(ddl);
        assertThat(response.datasets())
                .singleElement()
                .satisfies(dataset -> assertThat(dataset.dataScript()).isEqualTo(dml));
        assertThat(response.entities())
                .singleElement()
                .satisfies(entity -> {
                    assertThat(entity.entityName()).isEqualTo("LEHOI");
                    assertThat(entity.displayName()).isEqualTo("Lễ hội");
                    assertThat(entity.attributes())
                            .singleElement()
                            .satisfies(attribute -> {
                                assertThat(attribute.attributeName()).isEqualTo("MaLeHoi");
                                assertThat(attribute.description()).isEqualTo("Mã lễ hội");
                            });
                });
        assertThat(exam.getSpecificationId()).isEqualTo(21L);

        verify(examSchemaService).loadTemplateIntoSchema(any(String.class), org.mockito.ArgumentMatchers.eq(ddl),
                org.mockito.ArgumentMatchers.eq(dml));
        verify(examRepository).save(exam);
    }

    @Test
    void executeReturnsAttachedSpecificationWithoutCreatingDuplicate() {
        Exam exam = Exam.builder()
                .id(2L)
                .classId(3L)
                .specificationId(21L)
                .build();
        ExamSpecification specification = ExamSpecification.builder()
                .id(21L)
                .name("Đặc tả hiện có")
                .entities(List.of())
                .datasets(List.of())
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(11L);
        when(examRepository.findById(2L)).thenReturn(Optional.of(exam));
        when(classRepository.existsTeacherAccess(3L, 11L)).thenReturn(true);
        when(examSpecificationRepository.findById(21L)).thenReturn(Optional.of(specification));

        ExamSpecificationResponse response = usecase.execute(2L);

        assertThat(response.id()).isEqualTo(21L);
        verify(examQuestionRepository, never()).findByExamId(2L);
        verify(examSpecificationRepository, never()).save(any(ExamSpecification.class));
    }

    private ExamQuestion question(int orderIndex, QuestionType type, String correctQuery) {
        return ExamQuestion.builder()
                .examId(2L)
                .content("Câu " + orderIndex)
                .correctQuery(correctQuery)
                .points(BigDecimal.ONE)
                .orderIndex(orderIndex)
                .questionType(type)
                .build();
    }
}
