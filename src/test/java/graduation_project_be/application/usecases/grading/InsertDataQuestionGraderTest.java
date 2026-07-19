package graduation_project_be.application.usecases.grading;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.repositories.TestCaseRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SqlExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsertDataQuestionGraderTest {

    @Mock private ExamSchemaService examSchemaService;
    @Mock private TestCaseRepository testCaseRepository;

    private InsertDataQuestionGrader grader;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        GradingSupport support = new GradingSupport(examSchemaService, objectMapper, testCaseRepository);
        grader = new InsertDataQuestionGrader(examSchemaService, objectMapper, support);
    }

    @Test
    void explicitExtraRowRuleOverridesLegacyExtraRowSettings() {
        ExamQuestion question = insertQuestion("""
                {
                  "grading_payload": {
                    "grading_settings": {
                      "allow_extra_rows": true,
                      "penalty_per_extra_row": 0.2
                    },
                    "grading_rules": [
                      {"target":"ROW","condition":"IS_MISSING","action":"DEDUCT_POINTS","penalty_value":0.5},
                      {"target":"ROW","condition":"IS_EXTRA","action":"DEDUCT_POINTS","penalty_value":0.5}
                    ],
                    "tables": [{
                      "table_name": "Students",
                      "table_points": 1.0,
                      "primary_keys": ["id"],
                      "columns_to_grade": ["id"],
                      "expected_data": [{"id": 1}, {"id": 2}]
                    }]
                  }
                }
                """);
        when(examSchemaService.executeAdminSql(anyString()))
                .thenReturn(sqlRows(List.of(row(1), row(3))));

        ExamSubmission submission = new ExamSubmission();
        boolean passed = grader.gradeInsertDataByRubric("student_schema", question, submission, false);

        assertThat(passed).isFalse();
        assertThat(submission.getScoreEarned()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(submission.getErrorMessage()).contains("thiếu 1 dòng", "dư 1 dòng", "trừ 1.00 điểm");
    }

    @Test
    void reorderedRowsAreAcceptedWhenNoRowOrderRuleIsConfigured() {
        ExamQuestion question = insertQuestion("""
                {
                  "grading_payload": {
                    "grading_rules": [],
                    "tables": [{
                      "table_name": "Students",
                      "table_points": 1.0,
                      "primary_keys": ["id"],
                      "columns_to_grade": ["id"],
                      "expected_data": [{"id": 1}, {"id": 2}]
                    }]
                  }
                }
                """);
        when(examSchemaService.executeAdminSql(anyString()))
                .thenReturn(sqlRows(List.of(row(2), row(1))));

        ExamSubmission submission = new ExamSubmission();
        boolean passed = grader.gradeInsertDataByRubric("student_schema", question, submission, false);

        assertThat(passed).isTrue();
        assertThat(submission.getScoreEarned()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(submission.getErrorMessage()).isNull();
    }

    private ExamQuestion insertQuestion(String rubric) {
        return ExamQuestion.builder()
                .id(10L)
                .questionType(QuestionType.INSERT_DATA)
                .points(BigDecimal.ONE)
                .gradingRubric(rubric)
                .build();
    }

    private SqlExecutionResult sqlRows(List<Map<String, Object>> rows) {
        return SqlExecutionResult.builder()
                .resultSet(rows)
                .rowCount(rows.size())
                .build();
    }

    private Map<String, Object> row(int id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        return row;
    }
}
