package graduation_project_be.application.usecases.grading.createtable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.TableMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateSchemaGraphEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emitsCanonicalForeignKeyReferenceMismatchWithoutExtraForeignKey() throws Exception {
        JsonNode rubric = objectMapper.readTree("""
                [
                  {
                    "expected_name": "Student",
                    "columns": [
                      {"name": "class_id", "expected_type": "INT", "is_nullable": true}
                    ],
                    "constraints": [
                      {
                        "type": "FOREIGN_KEY",
                        "columns": ["class_id"],
                        "references_table": "Class",
                        "references_columns": ["id"]
                      }
                    ]
                  }
                ]
                """);

        TableMetadata actual = TableMetadata.builder()
                .tableName("Student")
                .columns(List.of(column("class_id", "INT", false, false, false, true)))
                .constraints(List.of(TableMetadata.ConstraintMetadata.builder()
                        .type("FOREIGN_KEY")
                        .columns(List.of("class_id"))
                        .referencesTable("Course")
                        .referencesColumns(List.of("id"))
                        .build()))
                .build();

        List<CreateSchemaEdit> edits = CreateSchemaGraphComparator.compare(
                CreateSchemaGraphBuilder.fromRubric(rubric, false),
                CreateSchemaGraphBuilder.fromMetadata(List.of(actual), false),
                false);

        assertEquals(1, edits.stream()
                .filter(edit -> "FOREIGN_KEY".equals(edit.target()) && "MISMATCH".equals(edit.condition()))
                .count());
        assertEquals(0, edits.stream()
                .filter(edit -> "FOREIGN_KEY".equals(edit.target()) && "IS_EXTRA".equals(edit.condition()))
                .count());
        assertEquals(0, edits.stream()
                .filter(edit -> "CONSTRAINT_LOCAL".equals(edit.target()) && "IS_MISSING".equals(edit.condition()))
                .count());
    }

    @Test
    void comparesCheckAndDefaultConstraints() throws Exception {
        JsonNode rubric = objectMapper.readTree("""
                [
                  {
                    "expected_name": "Student",
                    "columns": [
                      {"name": "score", "expected_type": "INT", "is_nullable": true},
                      {"name": "status", "expected_type": "INT", "is_nullable": true}
                    ],
                    "constraints": [
                      {"type": "CHECK", "columns": ["score"], "expression": "score BETWEEN 0 AND 10"},
                      {"type": "DEFAULT", "columns": ["status"], "default_value": "1"}
                    ]
                  }
                ]
                """);

        TableMetadata actual = TableMetadata.builder()
                .tableName("Student")
                .columns(List.of(
                        column("score", "INT", false, false, false, true),
                        column("status", "INT", false, false, false, true)))
                .constraints(List.of(
                        TableMetadata.ConstraintMetadata.builder()
                                .type("CHECK")
                                .columns(List.of("score"))
                                .expression("([score]>(0))")
                                .build(),
                        TableMetadata.ConstraintMetadata.builder()
                                .type("DEFAULT")
                                .columns(List.of("status"))
                                .defaultValue("((0))")
                                .build()))
                .build();

        List<CreateSchemaEdit> edits = CreateSchemaGraphComparator.compare(
                CreateSchemaGraphBuilder.fromRubric(rubric, false),
                CreateSchemaGraphBuilder.fromMetadata(List.of(actual), false),
                false);

        assertTrue(edits.stream().anyMatch(edit -> "CHECK".equals(edit.target()) && "EXPRESSION_MISMATCH".equals(edit.condition())));
        assertTrue(edits.stream().anyMatch(edit -> "DEFAULT".equals(edit.target()) && "VALUE_MISMATCH".equals(edit.condition())));
    }

    @Test
    void scorerUsesDefaultWeightsWhenRuleHasNoPenalty() throws Exception {
        JsonNode rubric = objectMapper.readTree("""
                {
                  "grading_rules": [
                    {"target": "FOREIGN_KEY", "condition": "IS_MISSING", "action": "DEDUCT_POINTS"}
                  ],
                  "tables": [
                    {
                      "expected_name": "Student",
                      "columns": [
                        {"name": "class_id", "expected_type": "INT", "is_nullable": true}
                      ],
                      "constraints": [
                        {
                          "type": "FOREIGN_KEY",
                          "columns": ["class_id"],
                          "references_table": "Class",
                          "references_columns": ["id"]
                        }
                      ]
                    }
                  ]
                }
                """);
        List<CreateSchemaEdit> edits = List.of(new CreateSchemaEdit(
                "FOREIGN_KEY", "IS_MISSING",
                "Student",
                List.of("class_id"),
                "FOREIGN_KEY",
                "Class",
                List.of("id"),
                "Class(id)",
                null,
                "Bang Student: thieu FK"));

        CreateWeightedEditScorer.CreateScoringResult result = CreateWeightedEditScorer.score(
                edits,
                rubric.path("tables"),
                rubric.path("grading_rules"),
                BigDecimal.TEN,
                false);

        assertFalse(result.allPassed());
        assertEquals(0, result.earnedPoints().compareTo(BigDecimal.valueOf(8).setScale(2)));
    }

    @Test
    void scorerDeductsPerEditWithoutConstraintLocalDoubleCount() throws Exception {
        JsonNode rubric = objectMapper.readTree("""
                {
                  "grading_rules": [
                    {"target": "FOREIGN_KEY", "condition": "IS_MISSING", "action": "DEDUCT_POINTS", "penalty_value": 0.3},
                    {"target": "CONSTRAINT_LOCAL", "condition": "IS_MISSING", "action": "DEDUCT_POINTS", "penalty_value": 0.15}
                  ],
                  "tables": [
                    {
                      "expected_name": "Child",
                      "columns": [
                        {"name": "ref_a", "expected_type": "INT", "is_nullable": true},
                        {"name": "ref_b", "expected_type": "INT", "is_nullable": true}
                      ],
                      "constraints": [
                        {"type": "FOREIGN_KEY", "columns": ["ref_a"], "references_table": "ParentA", "references_columns": ["id"]},
                        {"type": "FOREIGN_KEY", "columns": ["ref_b"], "references_table": "ParentB", "references_columns": ["id"]}
                      ]
                    }
                  ]
                }
                """);
        List<CreateSchemaEdit> edits = List.of(
                new CreateSchemaEdit(
                        "FOREIGN_KEY", "IS_MISSING",
                        "Child",
                        List.of("ref_a"),
                        "FOREIGN_KEY",
                        "ParentA",
                        List.of("id"),
                        "ParentA(id)",
                        null,
                        "Bang Child: thieu FK ref_a"),
                new CreateSchemaEdit(
                        "FOREIGN_KEY", "IS_MISSING",
                        "Child",
                        List.of("ref_b"),
                        "FOREIGN_KEY",
                        "ParentB",
                        List.of("id"),
                        "ParentB(id)",
                        null,
                        "Bang Child: thieu FK ref_b"));

        CreateWeightedEditScorer.CreateScoringResult result = CreateWeightedEditScorer.score(
                edits,
                rubric.path("tables"),
                rubric.path("grading_rules"),
                BigDecimal.valueOf(2),
                false);

        assertEquals(0, result.totalDeductions().compareTo(BigDecimal.valueOf(0.6).setScale(2)));
        assertEquals(0, result.earnedPoints().compareTo(BigDecimal.valueOf(1.4).setScale(2)));
    }

    @Test
    void scorerMapsNewCreateRuleTargetsToEditTypes() throws Exception {
        JsonNode rubric = objectMapper.readTree("""
                {
                  "grading_rules": [
                    {"target": "NULLABILITY", "condition": "NOT_EQUAL", "action": "DEDUCT_POINTS", "penalty_value": 0.11},
                    {"target": "IDENTITY", "condition": "NOT_EQUAL", "action": "DEDUCT_POINTS", "penalty_value": 0.12},
                    {"target": "CHECK", "condition": "EXPRESSION_MISMATCH", "action": "DEDUCT_POINTS", "penalty_value": 0.13},
                    {"target": "DEFAULT", "condition": "VALUE_MISMATCH", "action": "DEDUCT_POINTS", "penalty_value": 0.14},
                    {"target": "UNIQUE", "condition": "IS_MISSING", "action": "DEDUCT_POINTS", "penalty_value": 0.15}
                  ],
                  "tables": []
                }
                """);
        List<CreateSchemaEdit> edits = List.of(
                new CreateSchemaEdit("NULLABILITY", "NOT_EQUAL", "Student", List.of("name"),
                        null, null, List.of(), "NOT NULL", "NULL", "nullable sai"),
                new CreateSchemaEdit("IDENTITY", "NOT_EQUAL", "Student", List.of("id"),
                        null, null, List.of(), "IDENTITY", null, "identity sai"),
                new CreateSchemaEdit("CHECK", "EXPRESSION_MISMATCH", "Student", List.of("score"),
                        "CHECK", null, List.of(), "score BETWEEN 0 AND 10", "score > 0", "check sai"),
                new CreateSchemaEdit("DEFAULT", "VALUE_MISMATCH", "Student", List.of("status"),
                        "DEFAULT", null, List.of(), "1", "0", "default sai"),
                new CreateSchemaEdit("UNIQUE", "IS_MISSING", "Student", List.of("email"),
                        "UNIQUE", null, List.of(), "UNIQUE(email)", null, "unique thieu"));

        CreateWeightedEditScorer.CreateScoringResult result = CreateWeightedEditScorer.score(
                edits,
                rubric.path("tables"),
                rubric.path("grading_rules"),
                BigDecimal.TEN,
                false);

        assertEquals(0, result.totalDeductions().compareTo(BigDecimal.valueOf(0.65).setScale(2)));
        assertEquals(0, result.earnedPoints().compareTo(BigDecimal.valueOf(9.35).setScale(2)));
    }

    private static TableMetadata.ColumnMetadata column(
            String name,
            String rawType,
            boolean primary,
            boolean unique,
            boolean identity,
            boolean nullable) {
        return TableMetadata.ColumnMetadata.builder()
                .columnName(name)
                .rawDataType(rawType)
                .dataType(rawType)
                .isPrimaryKey(primary)
                .isUnique(unique)
                .isAutoIncrement(identity)
                .isNullable(nullable)
                .build();
    }
}

