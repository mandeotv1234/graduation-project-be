package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultFeedbackRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.GetStudentFeedbackResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.enums.GradingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetStudentFeedbackUsecaseTest {

    @Mock
    private ExamResultRepository examResultRepository;
    @Mock
    private ExamResultFeedbackRepository examResultFeedbackRepository;
    @Mock
    private ExamSubmissionRepository examSubmissionRepository;
    @Mock
    private ExamQuestionRepository examQuestionRepository;
    @Mock
    private ExamRepository examRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private AIService aiService;

    @Test
    void sendsDisplayOrderToAiAndMapsDraftBackToDatabaseQuestion() {
        ExamResult result = ExamResult.builder()
                .id(459L)
                .examId(25L)
                .studentId(10L)
                .attemptNumber(15)
                .totalScore(new BigDecimal("7.95"))
                .maxScore(BigDecimal.TEN)
                .submittedAt(LocalDateTime.of(2026, 7, 26, 9, 0))
                .status(GradingStatus.COMPLETED)
                .build();
        Exam exam = Exam.builder()
                .id(25L)
                .title("Bài thi SQL")
                .settings(ExamSettings.builder().allowReview(true).build())
                .build();
        ExamQuestion question = ExamQuestion.builder()
                .id(61L)
                .examId(25L)
                .content("Truy vấn dữ liệu")
                .correctQuery("SELECT 1")
                .points(BigDecimal.TEN)
                .orderIndex(1)
                .questionType(QuestionType.SELECT_QUERY)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(10L);
        when(examResultRepository.findById(459L)).thenReturn(Optional.of(result));
        when(examRepository.findById(25L)).thenReturn(Optional.of(exam));
        when(examResultFeedbackRepository.findByExamResultId(459L)).thenReturn(Optional.empty());
        when(examResultRepository.findByStudentIdAndExamIdIn(10L, List.of(25L)))
                .thenReturn(List.of(result));
        when(examQuestionRepository.findByExamId(25L)).thenReturn(List.of(question));
        when(examSubmissionRepository.findByExamIdAndStudentIdAndAttemptNumber(25L, 10L, 15))
                .thenReturn(List.of());
        when(aiService.generateStudentFeedback(any())).thenReturn(new AIService.StudentFeedbackDraft(
                "Câu 1 cần cải thiện.",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new AIService.StudentQuestionFeedbackDraft(
                        1L,
                        "Nhận xét dành cho câu 1.",
                        List.of(),
                        List.of()))));

        GetStudentFeedbackUsecase usecase = new GetStudentFeedbackUsecase(
                examResultRepository,
                examResultFeedbackRepository,
                examSubmissionRepository,
                examQuestionRepository,
                examRepository,
                currentUserService,
                aiService,
                new ObjectMapper().findAndRegisterModules());

        GetStudentFeedbackResponse response = usecase.execute(459L);

        ArgumentCaptor<AIService.StudentFeedbackContext> contextCaptor =
                ArgumentCaptor.forClass(AIService.StudentFeedbackContext.class);
        verify(aiService).generateStudentFeedback(contextCaptor.capture());
        AIService.StudentFeedbackQuestion aiQuestion = contextCaptor.getValue().questions().getFirst();

        assertThat(aiQuestion.questionId()).isEqualTo(1L);
        assertThat(aiQuestion.orderIndex()).isEqualTo(1);
        assertThat(response.overallFeedback()).isEqualTo("Câu 1 cần cải thiện.");
        assertThat(response.questionFeedbacks().getFirst().questionId()).isEqualTo(61L);
        assertThat(response.questionFeedbacks().getFirst().orderIndex()).isEqualTo(1);
        assertThat(response.questionFeedbacks().getFirst().diagnosis())
                .isEqualTo("Nhận xét dành cho câu 1.");
        verify(examResultFeedbackRepository).save(any());
    }

    @Test
    void detectsCachedAiFeedbackThatReferencesDatabaseQuestionIds() {
        GetStudentFeedbackResponse legacyFeedback = feedbackWithText(
                "Hai câu 62 và 63 đạt trọn điểm; câu 61 cần cải thiện.");
        GetStudentFeedbackResponse displayOrderFeedback = feedbackWithText(
                "Hai câu 2 và 3 đạt trọn điểm; câu 1 cần cải thiện.");
        GetStudentFeedbackResponse scoreMentionFeedback = feedbackWithText(
                "Câu 1 đạt 6 điểm và cần cải thiện cách trình bày.",
                List.of(questionFeedback(6L, 1)));

        assertThat(GetStudentFeedbackUsecase.hasLegacyDatabaseQuestionReferences(legacyFeedback))
                .isTrue();
        assertThat(GetStudentFeedbackUsecase.hasLegacyDatabaseQuestionReferences(displayOrderFeedback))
                .isFalse();
        assertThat(GetStudentFeedbackUsecase.hasLegacyDatabaseQuestionReferences(scoreMentionFeedback))
                .isFalse();
    }

    private GetStudentFeedbackResponse feedbackWithText(String overallFeedback) {
        return feedbackWithText(
                overallFeedback,
                List.of(
                        questionFeedback(61L, 1),
                        questionFeedback(62L, 2),
                        questionFeedback(63L, 3),
                        questionFeedback(64L, 4)));
    }

    private GetStudentFeedbackResponse feedbackWithText(
            String overallFeedback,
            List<GetStudentFeedbackResponse.QuestionFeedback> questionFeedbacks) {
        return new GetStudentFeedbackResponse(
                459L,
                25L,
                "Bài thi SQL",
                15,
                new BigDecimal("7.95"),
                BigDecimal.TEN,
                LocalDateTime.of(2026, 7, 26, 9, 0),
                true,
                LocalDateTime.of(2026, 7, 26, 9, 1),
                null,
                overallFeedback,
                null,
                List.of(),
                List.of(),
                List.of(),
                questionFeedbacks);
    }

    private GetStudentFeedbackResponse.QuestionFeedback questionFeedback(Long questionId, int orderIndex) {
        return new GetStudentFeedbackResponse.QuestionFeedback(
                questionId,
                orderIndex,
                "SELECT_QUERY",
                BigDecimal.ZERO,
                BigDecimal.ONE,
                null,
                List.of(),
                List.of(),
                List.of());
    }
}
