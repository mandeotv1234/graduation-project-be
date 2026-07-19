package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.ExamTemplateQuestionRepository;
import graduation_project_be.application.port.repositories.ExamTemplateRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamTemplate;
import graduation_project_be.domain.models.ExamTemplateQuestion;
import graduation_project_be.domain.models.ExamTemplateSpecificationSnapshot;
import graduation_project_be.domain.models.QuestionType;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloneExamTemplateUsecaseTest {

    @Mock private ExamTemplateRepository examTemplateRepository;
    @Mock private ExamTemplateQuestionRepository examTemplateQuestionRepository;
    @Mock private ClassRepository classRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private ExamSpecificationRepository examSpecificationRepository;
    @Mock private ExamRepository examRepository;
    @Mock private ExamQuestionRepository examQuestionRepository;

    private CloneExamTemplateUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new CloneExamTemplateUsecase(
                examTemplateRepository,
                examTemplateQuestionRepository,
                classRepository,
                currentUserService,
                examSpecificationRepository,
                examRepository,
                examQuestionRepository);
    }

    @Test
    void clonePreservesQuestionGradingRubric() {
        String gradingRubric = "{\"grading_payload\":{\"test_cases\":[{\"case_id\":\"TC_01\"}]}}";
        ExamTemplateSpecificationSnapshot snapshot = ExamTemplateSpecificationSnapshot.builder()
                .name("CSDL")
                .ddlScript("CREATE TABLE Students (id INT)")
                .entities(List.of())
                .datasets(List.of())
                .build();
        ExamTemplate template = ExamTemplate.builder()
                .id(7L)
                .title("Đề mẫu")
                .specificationSnapshot(snapshot)
                .build();
        ExamTemplateQuestion templateQuestion = ExamTemplateQuestion.builder()
                .content("Truy vấn danh sách")
                .correctQuery("SELECT * FROM Students")
                .questionType(QuestionType.SELECT_QUERY)
                .points(BigDecimal.ONE)
                .orderIndex(1)
                .gradingRubric(gradingRubric)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(11L);
        when(classRepository.existsTeacherAccess(3L, 11L)).thenReturn(true);
        when(examTemplateRepository.findVisibleById(7L)).thenReturn(Optional.of(template));
        when(examSpecificationRepository.save(any(ExamSpecification.class)))
                .thenAnswer(invocation -> {
                    ExamSpecification specification = invocation.getArgument(0);
                    specification.setId(21L);
                    return specification;
                });
        when(examRepository.save(any(Exam.class))).thenAnswer(invocation -> {
            Exam exam = invocation.getArgument(0);
            exam.setId(31L);
            return exam;
        });
        when(examTemplateQuestionRepository.findByTemplateId(7L))
                .thenReturn(List.of(templateQuestion));

        usecase.execute(7L, 3L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExamQuestion>> questionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(examQuestionRepository).saveAll(questionsCaptor.capture());
        assertThat(questionsCaptor.getValue())
                .singleElement()
                .extracting(ExamQuestion::getGradingRubric)
                .isEqualTo(gradingRubric);
    }
}
