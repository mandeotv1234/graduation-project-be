package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.TableMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateTableRubricEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsMultipleForeignKeysOnTheSameColumn() throws Exception {
        TableMetadata actualTable = childTableWithForeignKeys(List.of(
                foreignKey("FK_Child_ParentA", "ParentA"),
                foreignKey("FK_Child_ParentB", "ParentB")));

        CreateTableRubricEvaluator.CreateTableRubricGradeResult result =
                CreateTableRubricEvaluator.evaluate(
                        rubricWithTwoForeignKeys(),
                        List.of(actualTable),
                        BigDecimal.valueOf(2));

        assertTrue(result.allPassed());
        assertEquals(0, result.totalDeductions().compareTo(BigDecimal.ZERO));
        assertEquals(1, actualTable.getColumns().size());
        assertEquals(2, actualTable.getForeignKeys().size());
    }

    @Test
    void reportsMissingForeignKeyWhenOneOfMultipleRelationsIsAbsent() throws Exception {
        TableMetadata actualTable = childTableWithForeignKeys(List.of(
                foreignKey("FK_Child_ParentA", "ParentA")));

        CreateTableRubricEvaluator.CreateTableRubricGradeResult result =
                CreateTableRubricEvaluator.evaluate(
                        rubricWithTwoForeignKeys(),
                        List.of(actualTable),
                        BigDecimal.valueOf(2));

        assertFalse(result.allPassed());
        assertTrue(result.errorMessage().contains("FOREIGN_KEY")
                || result.errorMessage().contains("rang buoc"));
    }

    private JsonNode rubricWithTwoForeignKeys() throws Exception {
        return objectMapper.readTree("""
                {
                  "grading_payload": {
                    "grading_settings": {
                      "deduction_mode": true
                    },
                    "tables": [
                      {
                        "expected_name": "Child",
                        "missing_table_penalty": 2.0,
                        "columns": [
                          {
                            "name": "ref_id",
                            "expected_type": "INT",
                            "missing_column_penalty": 0.5,
                            "type_mismatch_penalty": 0.5
                          }
                        ],
                        "constraints": [
                          {
                            "type": "FOREIGN_KEY",
                            "columns": ["ref_id"],
                            "references_table": "ParentA",
                            "references_columns": ["id"],
                            "missing_constraint_penalty": 0.5
                          },
                          {
                            "type": "FOREIGN_KEY",
                            "columns": ["ref_id"],
                            "references_table": "ParentB",
                            "references_columns": ["id"],
                            "missing_constraint_penalty": 0.5
                          }
                        ]
                      }
                    ]
                  }
                }
                """);
    }

    private TableMetadata childTableWithForeignKeys(List<TableMetadata.ForeignKeyMetadata> foreignKeys) {
        return TableMetadata.builder()
                .tableName("Child")
                .columns(List.of(TableMetadata.ColumnMetadata.builder()
                        .columnName("ref_id")
                        .dataType("int")
                        .rawDataType("INT")
                        .isForeignKey(true)
                        .referencesTable("ParentA")
                        .referencesColumn("id")
                        .build()))
                .foreignKeys(foreignKeys)
                .build();
    }

    private TableMetadata.ForeignKeyMetadata foreignKey(String constraintName, String referencesTable) {
        return TableMetadata.ForeignKeyMetadata.builder()
                .constraintName(constraintName)
                .columns(List.of("ref_id"))
                .referencesTable(referencesTable)
                .referencesColumns(List.of("id"))
                .build();
    }
}
