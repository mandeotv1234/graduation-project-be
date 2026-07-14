package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.TestCaseRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.GradingTraceCollector;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SqlExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SelectQuestionGraderTest {

    @Mock private ExamSchemaService examSchemaService;
    @Mock private TestCaseRepository testCaseRepository;

    private SelectQuestionGrader grader;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        GradingSupport support = new GradingSupport(examSchemaService, objectMapper, testCaseRepository);
        grader = new SelectQuestionGrader(examSchemaService, objectMapper, support);
    }

    @Test
    void gradeSelectByRubricTestCases_normalizesLegacyPointRuleBeforeRuntimeScoring() {
        ExamQuestion question = selectQuestion("""
                {
                  "grading_payload": {
                    "grading_rules": [
                      {"target":"ROW","condition":"IS_MISSING","action":"DEDUCT_POINTS","penalty_value":0.5}
                    ],
                    "global_grading_rules": {"strict_ordering": false},
                    "test_cases": [
                      {"case_id":"TC_01","case_name":"Missing rows","penalty_value":0.3}
                    ]
                  }
                }
                """);
        when(examSchemaService.executeSql(anyString(), anyString()))
                .thenReturn(sqlRows(List.of()))
                .thenReturn(sqlRows(List.of(Map.of("id", 1), Map.of("id", 2))));

        GradeDecision decision = grader.gradeSelectByRubricTestCases(
                null,
                null,
                List.of(question),
                "exam_1_student_1_att_1",
                question,
                "student query");

        assertThat(decision.scoreEarned()).isEqualByComparingTo(new BigDecimal("1.30"));
        assertThat(decision.errorMessage()).contains("trừ 0.20 điểm");
    }

    @Test
    void gradeSelectAcrossDatasets_writesSingleTraceWithActualCappedDeduction() {
        ExamQuestion question = selectQuestion("""
                {
                  "grading_rules": [
                    {"target":"ROW","condition":"IS_MISSING","action":"DEDUCT_POINTS","penalty_value":0.75}
                  ]
                }
                """);
        question.setPoints(BigDecimal.ONE);
        when(examSchemaService.executeSql(anyString(), anyString()))
                .thenReturn(sqlRows(List.of()))
                .thenReturn(sqlRows(List.of(Map.of("id", 1), Map.of("id", 2))));

        GradingTraceCollector.start();
        GradeDecision decision = grader.gradeSelectAcrossDatasets(
                null,
                "exam_1_student_1_att_1",
                question,
                "student query");
        List<GradingTraceItem> trace = GradingTraceCollector.finish();

        assertThat(decision.scoreEarned()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(trace)
                .filteredOn(item -> GradingTraceItem.KIND_RUBRIC_RULE.equals(item.kind()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.deductedPoints()).isEqualByComparingTo(BigDecimal.ONE);
                    assertThat(item.maxPoints()).isEqualByComparingTo(BigDecimal.ONE);
                    assertThat(item.message()).contains("ROW/IS_MISSING");
                });
    }

    private ExamQuestion selectQuestion(String rubric) {
        return ExamQuestion.builder()
                .id(10L)
                .questionType(QuestionType.SELECT_QUERY)
                .correctQuery("teacher query")
                .points(new BigDecimal("1.5"))
                .gradingRubric(rubric)
                .build();
    }

    private SqlExecutionResult sqlRows(List<Map<String, Object>> rows) {
        return SqlExecutionResult.builder()
                .resultSet(rows)
                .rowCount(rows.size())
                .build();
    }
}
