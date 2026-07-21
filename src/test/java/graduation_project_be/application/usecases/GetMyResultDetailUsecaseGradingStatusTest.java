package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GetMyResultDetailRequest;
import graduation_project_be.application.usecases.response.GetExamResultDetailResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.GradingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetMyResultDetailUsecaseGradingStatusTest {

    @Mock private ExamResultRepository examResultRepository;
    @Mock private ExamSubmissionRepository examSubmissionRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private ExamRepository examRepository;
    @Mock private UserRepository userRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private GetMyResultDetailUsecase usecase;

    @Test
    void pendingResultCanBePolledWhenReviewIsDisabled() {
        stubOwnerAndExam(GradingStatus.PENDING, false, false);

        GetExamResultDetailResponse response = usecase.execute(request());

        assertEquals(GradingStatus.PENDING, response.status());
        assertNull(response.totalScore());
        assertEquals(List.of(), response.questionResults());
        verifyNoInteractions(examQuestionRepository, examSubmissionRepository);
    }

    @Test
    void completedResultOnlyExposesStatusWhenResultDisplayIsDisabled() {
        stubOwnerAndExam(GradingStatus.COMPLETED, false, false);

        GetExamResultDetailResponse response = usecase.execute(request());

        assertEquals(GradingStatus.COMPLETED, response.status());
        assertNull(response.totalScore());
        assertNull(response.maxScore());
        assertEquals(0, response.correctCount());
        assertEquals(List.of(), response.questionResults());
        verifyNoInteractions(examQuestionRepository, examSubmissionRepository);
    }

    @Test
    void showResultAfterSubmitReturnsScoresWithoutLeakingCorrectAnswer() {
        stubOwnerAndExam(GradingStatus.COMPLETED, false, true);
        ExamQuestion question = ExamQuestion.builder()
                .id(31L)
                .examId(9L)
                .content("Liệt kê chuyến xe")
                .correctQuery("SELECT * FROM ChuyenXe")
                .questionType(QuestionType.SELECT_QUERY)
                .points(BigDecimal.TEN)
                .build();
        ExamSubmission submission = ExamSubmission.builder()
                .id(41L)
                .examId(9L)
                .questionId(31L)
                .studentId(21L)
                .attemptNumber(1)
                .studentQuery("SELECT MaChuyen FROM ChuyenXe")
                .isCorrect(true)
                .scoreEarned(BigDecimal.TEN)
                .build();
        when(examQuestionRepository.findByExamId(9L)).thenReturn(List.of(question));
        when(examSubmissionRepository.findByExamIdAndStudentIdAndAttemptNumber(9L, 21L, 1))
                .thenReturn(List.of(submission));

        GetExamResultDetailResponse response = usecase.execute(request());

        assertEquals(1, response.questionResults().size());
        assertEquals(BigDecimal.TEN, response.questionResults().getFirst().scoreEarned());
        assertNull(response.questionResults().getFirst().correctQuery());
    }

    private GetMyResultDetailRequest request() {
        return new GetMyResultDetailRequest(99L);
    }

    private void stubOwnerAndExam(
            GradingStatus status,
            boolean allowReview,
            boolean showResultAfterSubmit) {
        ExamResult result = ExamResult.builder()
                .id(99L)
                .examId(9L)
                .studentId(21L)
                .attemptNumber(1)
                .totalScore(status == GradingStatus.COMPLETED ? BigDecimal.TEN : BigDecimal.ZERO)
                .maxScore(BigDecimal.TEN)
                .correctCount(status == GradingStatus.COMPLETED ? 1 : 0)
                .totalQuestions(1)
                .submittedAt(LocalDateTime.of(2026, 7, 21, 20, 30))
                .status(status)
                .build();
        Exam exam = Exam.builder()
                .id(9L)
                .settings(ExamSettings.builder()
                        .allowReview(allowReview)
                        .showResultAfterSubmit(showResultAfterSubmit)
                        .build())
                .build();
        User student = User.builder()
                .id(21L)
                .fullName("Nguyễn Thắng Hữu")
                .email("24122003@student.hcmus.edu.vn")
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(21L);
        when(examResultRepository.findById(99L)).thenReturn(Optional.of(result));
        when(examRepository.findById(9L)).thenReturn(Optional.of(exam));
        when(userRepository.findById(21L)).thenReturn(Optional.of(student));
    }
}
