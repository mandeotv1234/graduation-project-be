package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.TestCaseRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineQuestionGraderMetadataGateTest {

    @Mock
    private ExamSchemaService examSchemaService;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private GradingSupport support;

    @Test
    void missingFunctionParameterModeDefaultsToInForLegacyRubric() {
        String rubric = """
                {
                  "grading_payload": {
                    "routines": [{
                      "expected_name": "FN_Test",
                      "expected_type": "FUNCTION",
                      "expected_return_type": "INT",
                      "parameters": [{"name": "value", "expected_type": "INT"}]
                    }]
                  }
                }
                """;
        ExamQuestion question = ExamQuestion.builder()
                .id(1L)
                .questionType(QuestionType.FUNCTION)
                .points(BigDecimal.TEN)
                .gradingRubric(rubric)
                .build();
        ExamSubmission submission = new ExamSubmission();
        RoutineMetadata actual = RoutineMetadata.builder()
                .routineName("FN_Test")
                .routineType("FUNCTION")
                .dataType("INT")
                .parameters(List.of(RoutineMetadata.ParameterMetadata.builder()
                        .parameterName("value")
                        .dataType("INT")
                        .parameterMode("IN")
                        .build()))
                .build();

        when(examSchemaService.extractRoutineMetadata("teacher_schema")).thenReturn(List.of(actual));
        when(examSchemaService.extractRoutineMetadata("student_schema")).thenReturn(List.of(actual));
        when(testCaseRepository.findByQuestionId(1L)).thenReturn(List.of(new TestCase()));
        when(support.gradeByTestCases(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    ExamSubmission gradedSubmission = invocation.getArgument(3);
                    gradedSubmission.setScoreEarned(BigDecimal.ONE);
                    return true;
                });

        RoutineQuestionGrader grader = new RoutineQuestionGrader(
                examSchemaService, new ObjectMapper(), testCaseRepository, support);

        assertTrue(grader.gradeRoutineAlgorithmic(
                "student_schema", "teacher_schema", question, submission));
        assertEquals(0, BigDecimal.TEN.compareTo(submission.getScoreEarned()));
    }
}
