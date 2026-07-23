package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.CreateExamQuestionRequest;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateExamQuestionUsecaseValidationTest {

    @Mock private ClassRepository classRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;
    @Mock private ExamRepository examRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private ExamSpecificationRepository examSpecificationRepository;
    @Mock private AIService aiService;

    @Test
    void rejectsQuestionThatMakesExamTotalExceedTen() {
        Exam exam = Exam.builder().id(2L).classId(3L).build();
        ExamQuestion existingQuestion = ExamQuestion.builder()
                .id(10L)
                .examId(2L)
                .points(new BigDecimal("9.0"))
                .build();
        when(currentUserService.getCurrentUserId()).thenReturn(11L);
        when(examRepository.findById(2L)).thenReturn(Optional.of(exam));
        when(classRepository.existsTeacherAccess(3L, 11L)).thenReturn(true);
        when(examQuestionRepository.findByExamId(2L)).thenReturn(List.of(existingQuestion));

        CreateExamQuestionUsecase usecase = new CreateExamQuestionUsecase(
                classRepository,
                examQuestionRepository,
                examRepository,
                currentUserService,
                examSpecificationRepository,
                aiService);
        CreateExamQuestionRequest request = new CreateExamQuestionRequest(
                2L,
                "Liệt kê dữ liệu",
                "SELECT 1",
                2,
                new BigDecimal("1.1"),
                2,
                "SELECT_QUERY",
                null);

        assertThatThrownBy(() -> usecase.execute(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10 điểm");
        verify(examQuestionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
