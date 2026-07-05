package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.TestCaseRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SqlExecutionResult;
import graduation_project_be.domain.models.TestCase;
import graduation_project_be.domain.models.enums.VerificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GradingSupportExecutionStatusTest {

    private static final String EXPECTED_ERROR = "__GRAD_EXECUTION_STATUS__:ERROR";

    @Mock
    private ExamSchemaService examSchemaService;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Test
    void expectedTriggerRejectionPassesWhenStudentInvocationAlsoFails() {
        GradingSupport support = new GradingSupport(examSchemaService, new ObjectMapper(), testCaseRepository);
        ExamQuestion question = triggerQuestion();
        ExamSubmission submission = new ExamSubmission();
        when(testCaseRepository.findByQuestionId(1L)).thenReturn(List.of(rejectionTestCase()));
        when(examSchemaService.executeSqlBatchAsSchemaUser(eq("student_schema"), anyString()))
                .thenThrow(new RuntimeException("The transaction ended in the trigger. The batch has been aborted."));

        boolean passed = support.gradeByTestCases("student_schema", "teacher_schema", question, submission);

        assertTrue(passed);
        assertEquals(BigDecimal.ONE, submission.getScoreEarned());
    }

    @Test
    void expectedTriggerRejectionFailsWhenStudentInvocationSucceeds() {
        GradingSupport support = new GradingSupport(examSchemaService, new ObjectMapper(), testCaseRepository);
        ExamQuestion question = triggerQuestion();
        ExamSubmission submission = new ExamSubmission();
        when(testCaseRepository.findByQuestionId(1L)).thenReturn(List.of(rejectionTestCase()));
        when(examSchemaService.executeSqlBatchAsSchemaUser(eq("student_schema"), anyString()))
                .thenReturn(SqlExecutionResult.builder().resultSet(List.of()).build());

        boolean passed = support.gradeByTestCases("student_schema", "teacher_schema", question, submission);

        assertFalse(passed);
        assertEquals(BigDecimal.ZERO, submission.getScoreEarned());
        assertTrue(submission.getErrorMessage().contains("mong đợi='ERROR', thực tế='OK'"));

        ArgumentCaptor<String> batchCaptor = ArgumentCaptor.forClass(String.class);
        verify(examSchemaService).executeSqlBatchAsSchemaUser(eq("student_schema"), batchCaptor.capture());
        assertFalse(batchCaptor.getValue().contains("SELECT COUNT(*)"));
    }

    private ExamQuestion triggerQuestion() {
        return ExamQuestion.builder()
                .id(1L)
                .questionType(QuestionType.TRIGGER)
                .points(BigDecimal.TEN)
                .build();
    }

    private TestCase rejectionTestCase() {
        return TestCase.builder()
                .questionId(1L)
                .caseName("Trigger từ chối INSERT một dòng có NhanCong âm")
                .scoreWeight(BigDecimal.ONE)
                .verificationType(VerificationType.SIDE_EFFECT)
                .invocationQuery("INSERT INTO [{SCHEMA}].HANGMUC (NhanCong) VALUES (-1)")
                .validationQuery("SELECT COUNT(*) FROM [{SCHEMA}].HANGMUC WHERE NhanCong < 0")
                .expectedValue(EXPECTED_ERROR)
                .build();
    }
}
