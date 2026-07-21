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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GradingSupportWeightNormalizationTest {

    private static final String EXECUTION_OK = "__GRAD_EXECUTION_STATUS__:OK";

    @Mock
    private ExamSchemaService examSchemaService;

    @Mock
    private TestCaseRepository testCaseRepository;

    private GradingSupport support;

    @BeforeEach
    void setUp() {
        support = new GradingSupport(examSchemaService, new ObjectMapper(), testCaseRepository);
    }

    @Test
    void allRoundedThirdsPassingEarnsExactlyOne() {
        List<TestCase> testCases = List.of(
                testCase(1, "PASS_1", "0.33"),
                testCase(2, "PASS_2", "0.33"),
                testCase(3, "PASS_3", "0.33"));
        stubTestCases(testCases);

        ExamSubmission submission = new ExamSubmission();
        boolean passed = support.gradeByTestCases(
                "student_schema", "teacher_schema", question(), submission);

        assertTrue(passed);
        assertEquals(0, BigDecimal.ONE.compareTo(submission.getScoreEarned()));
    }

    @Test
    void legacyWeightsAreNormalizedBeforePartialScoring() {
        List<TestCase> testCases = List.of(
                testCase(1, "PASS_1", "0.50"),
                testCase(2, "PASS_2", "0.50"),
                testCase(3, "FAIL_3", "0.50"),
                testCase(4, "FAIL_4", "0.50"),
                testCase(5, "FAIL_5", "0.50"));
        stubTestCases(testCases);

        ExamSubmission submission = new ExamSubmission();
        boolean passed = support.gradeByTestCases(
                "student_schema", "teacher_schema", question(), submission);

        assertFalse(passed);
        assertEquals(0, new BigDecimal("0.4").compareTo(submission.getScoreEarned()));
    }

    @Test
    void passingZeroWeightCaseDoesNotEarnPoints() {
        List<TestCase> testCases = List.of(
                testCase(1, "FAIL_WEIGHTED", "1"),
                testCase(2, "PASS_ZERO", "0"));
        stubTestCases(testCases);

        ExamSubmission submission = new ExamSubmission();
        boolean passed = support.gradeByTestCases(
                "student_schema", "teacher_schema", question(), submission);

        assertFalse(passed);
        assertEquals(0, BigDecimal.ZERO.compareTo(submission.getScoreEarned()));
    }

    @Test
    void failingZeroWeightCaseStillMarksTheQuestionIncorrect() {
        List<TestCase> testCases = List.of(
                testCase(1, "PASS_WEIGHTED", "1"),
                testCase(2, "FAIL_ZERO", "0"));
        stubTestCases(testCases);

        ExamSubmission submission = new ExamSubmission();
        boolean passed = support.gradeByTestCases(
                "student_schema", "teacher_schema", question(), submission);

        assertFalse(passed);
        assertEquals(0, BigDecimal.ONE.compareTo(submission.getScoreEarned()));
    }

    private void stubTestCases(List<TestCase> testCases) {
        when(testCaseRepository.findByQuestionId(1L)).thenReturn(testCases);
        when(examSchemaService.executeSqlBatchAsSchemaUser(eq("student_schema"), anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(1, String.class);
                    if (sql.contains("FAIL_")) {
                        throw new RuntimeException("Expected test failure");
                    }
                    return SqlExecutionResult.builder().resultSet(List.of()).build();
                });
    }

    private ExamQuestion question() {
        return ExamQuestion.builder()
                .id(1L)
                .questionType(QuestionType.FUNCTION)
                .points(BigDecimal.TEN)
                .build();
    }

    private TestCase testCase(int order, String invocationMarker, String weight) {
        return TestCase.builder()
                .questionId(1L)
                .orderIndex(order)
                .caseName(invocationMarker)
                .scoreWeight(new BigDecimal(weight))
                .verificationType(VerificationType.EXECUTION_STATUS)
                .invocationQuery(invocationMarker)
                .expectedValue(EXECUTION_OK)
                .build();
    }
}
