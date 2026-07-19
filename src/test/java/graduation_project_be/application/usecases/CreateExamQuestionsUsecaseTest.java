package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.PdfStorageService;
import graduation_project_be.application.port.services.PdfTextExtractor;
import graduation_project_be.application.usecases.request.CreateExamQuestionsRequest;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.infrastructure.services.ExpectedValueDeriver;
import graduation_project_be.infrastructure.services.RubricToTestCaseTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateExamQuestionsUsecaseTest {

    @Mock private ClassRepository classRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private ExamRepository examRepository;
    @Mock private ExamSpecificationRepository examSpecificationRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private AIService aiService;
    @Mock private PdfStorageService pdfStorageService;
    @Mock private PdfTextExtractor pdfTextExtractor;
    @Mock private RubricToTestCaseTransformer rubricTransformer;
    @Mock private ExpectedValueDeriver expectedValueDeriver;

    private CreateExamQuestionsUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new CreateExamQuestionsUsecase(
                classRepository,
                examQuestionRepository,
                examRepository,
                examSpecificationRepository,
                currentUserService,
                aiService,
                pdfStorageService,
                pdfTextExtractor,
                rubricTransformer,
                expectedValueDeriver);

        Exam exam = Exam.builder()
                .id(2L)
                .classId(3L)
                .pdfFilePath("uploads/exams/pdf/de-bai.pdf")
                .build();
        when(currentUserService.getCurrentUserId()).thenReturn(11L);
        when(examRepository.findById(2L)).thenReturn(Optional.of(exam));
        when(classRepository.existsTeacherAccess(3L, 11L)).thenReturn(true);
    }

    @Test
    void executeUsesExtractedSchemaAndFullPdfText() {
        String content = "Tạo bảng và các ràng buộc cần thiết cho các bảng trên";
        String schemaContext = "CREATE TABLE LEHOI (MaLeHoi CHAR(4) PRIMARY KEY);";
        String pdfText = "MÔ TẢ CƠ SỞ DỮ LIỆU: LEHOI, TIETMUC, TC_LH";
        String generationContext = "=== SCHEMA TRÍCH XUẤT TỪ ĐỀ ===\n"
                + schemaContext
                + "\n\n=== TOÀN BỘ NỘI DUNG FILE PDF ===\n"
                + pdfText;
        CreateExamQuestionsRequest request = request(content, schemaContext);

        when(pdfStorageService.loadPdf("uploads/exams/pdf/de-bai.pdf"))
                .thenReturn(new byte[] {1, 2, 3});
        when(pdfTextExtractor.extract(any(byte[].class))).thenReturn(pdfText);
        when(aiService.generateSqlAnswer(content, "CREATE_TABLE", generationContext))
                .thenReturn(new AIService.GeneratedQuestion(
                        "CREATE TABLE LEHOI (MaLeHoi CHAR(4) PRIMARY KEY)",
                        "SELECT 1"));
        when(examQuestionRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        usecase.execute(request);

        verify(aiService).generateSqlAnswer(content, "CREATE_TABLE", generationContext);
        verify(examSpecificationRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void executeRejectsEmptyAiAnswerInsteadOfSavingBrokenPlaceholder() {
        String content = "Tạo bảng và các ràng buộc cần thiết cho các bảng trên";
        String schemaContext = "CREATE TABLE LEHOI (MaLeHoi CHAR(4) PRIMARY KEY);";
        String pdfText = "MÔ TẢ CƠ SỞ DỮ LIỆU: LEHOI, TIETMUC, TC_LH";
        String generationContext = "=== SCHEMA TRÍCH XUẤT TỪ ĐỀ ===\n"
                + schemaContext
                + "\n\n=== TOÀN BỘ NỘI DUNG FILE PDF ===\n"
                + pdfText;
        CreateExamQuestionsRequest request = request(content, schemaContext);

        when(pdfStorageService.loadPdf("uploads/exams/pdf/de-bai.pdf"))
                .thenReturn(new byte[] {1, 2, 3});
        when(pdfTextExtractor.extract(any(byte[].class))).thenReturn(pdfText);
        when(aiService.generateSqlAnswer(content, "CREATE_TABLE", generationContext))
                .thenReturn(new AIService.GeneratedQuestion(null, null));

        assertThatThrownBy(() -> usecase.execute(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Câu 1")
                .hasMessageContaining("không thể sinh đáp án SQL");

        verify(examQuestionRepository, never()).saveAll(anyList());
    }

    private CreateExamQuestionsRequest request(String content, String schemaContext) {
        return new CreateExamQuestionsRequest(
                2L,
                schemaContext,
                List.of(new CreateExamQuestionsRequest.QuestionItem(
                        content,
                        "",
                        "",
                        2,
                        BigDecimal.valueOf(2.5),
                        1,
                        "CREATE_TABLE",
                        null)));
    }
}
