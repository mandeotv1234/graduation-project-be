package graduation_project_be.application.usecases.grading.createtable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CreateSchemaGraphComparator {

    private CreateSchemaGraphComparator() {
    }

    private static int countColumnOverlap(CreateSchemaGraph.TableNode exp, CreateSchemaGraph.TableNode act, boolean caseSensitive) {
        int count = 0;
        for (String col : exp.columns().keySet()) {
            if (act.columns().containsKey(col)) count++;
        }
        return count;
    }

    public static List<CreateSchemaEdit> compare(
            CreateSchemaGraph expected,
            CreateSchemaGraph actual,
            boolean caseSensitive) {
        List<CreateSchemaEdit> edits = new ArrayList<>();
        Map<String, CreateSchemaGraph.TableNode> expectedTables = expected.tables();
        Map<String, CreateSchemaGraph.TableNode> actualTables = actual.tables();

        List<CreateSchemaGraph.TableNode> unmappedExpected = new ArrayList<>();
        List<CreateSchemaGraph.TableNode> unmappedActual = new ArrayList<>();
        java.util.Map<String, CreateSchemaGraph.TableNode> mappedTables = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, CreateSchemaGraph.TableNode> expectedEntry : expectedTables.entrySet()) {
            String key = expectedEntry.getKey();
            CreateSchemaGraph.TableNode actualTable = actualTables.get(key);
            if (actualTable != null) {
                mappedTables.put(key, actualTable);
            } else {
                unmappedExpected.add(expectedEntry.getValue());
            }
        }

        for (Map.Entry<String, CreateSchemaGraph.TableNode> actualEntry : actualTables.entrySet()) {
            if (!expectedTables.containsKey(actualEntry.getKey())) {
                unmappedActual.add(actualEntry.getValue());
            }
        }

        if (unmappedExpected.size() == 1 && unmappedActual.size() == 1) {
            CreateSchemaGraph.TableNode exp = unmappedExpected.get(0);
            CreateSchemaGraph.TableNode act = unmappedActual.get(0);
            mappedTables.put(CreateSchemaNames.normalizeIdentifier(exp.name(), caseSensitive), act);
            edits.add(edit("TABLE", "NOT_EQUAL", exp.name(), List.of(), null, null, List.of(), exp.name(), act.name(), "Sai tên bảng " + exp.name() + " thành " + act.name()));
            unmappedExpected.clear();
            unmappedActual.clear();
        } else {
            List<CreateSchemaGraph.TableNode> newlyMappedExp = new ArrayList<>();
            List<CreateSchemaGraph.TableNode> newlyMappedAct = new ArrayList<>();
            for (CreateSchemaGraph.TableNode exp : unmappedExpected) {
                CreateSchemaGraph.TableNode bestMatch = null;
                int maxOverlap = 0;
                for (CreateSchemaGraph.TableNode act : unmappedActual) {
                    if (newlyMappedAct.contains(act)) continue;
                    int overlap = countColumnOverlap(exp, act, caseSensitive);
                    if (overlap > maxOverlap) {
                        maxOverlap = overlap;
                        bestMatch = act;
                    }
                }
                if (bestMatch != null && maxOverlap > 0) {
                    newlyMappedExp.add(exp);
                    newlyMappedAct.add(bestMatch);
                    mappedTables.put(CreateSchemaNames.normalizeIdentifier(exp.name(), caseSensitive), bestMatch);
                    edits.add(edit("TABLE", "NOT_EQUAL", exp.name(), List.of(), null, null, List.of(), exp.name(), bestMatch.name(), "Sai tên bảng " + exp.name() + " thành " + bestMatch.name()));
                }
            }
            unmappedExpected.removeAll(newlyMappedExp);
            unmappedActual.removeAll(newlyMappedAct);
        }

        for (CreateSchemaGraph.TableNode exp : unmappedExpected) {
            edits.add(edit("TABLE", "IS_MISSING", exp.name(), List.of(), null, null, List.of(), exp.name(), null, "Thiếu bảng " + exp.name()));
        }

        for (CreateSchemaGraph.TableNode act : unmappedActual) {
            edits.add(edit("TABLE", "IS_EXTRA", act.name(), List.of(), null, null, List.of(), null, act.name(), "Dư bảng " + act.name()));
        }

        for (Map.Entry<String, CreateSchemaGraph.TableNode> entry : mappedTables.entrySet()) {
            CreateSchemaGraph.TableNode expectedTable = null;
            for(CreateSchemaGraph.TableNode t : expectedTables.values()) {
                if(CreateSchemaNames.normalizeIdentifier(t.name(), caseSensitive).equals(entry.getKey())) {
                    expectedTable = t; break;
                }
            }
            CreateSchemaGraph.TableNode actualTable = entry.getValue();
            
            if (expectedTable != null && actualTable != null) {
                compareColumns(edits, expectedTable, actualTable, caseSensitive);
                comparePrimaryOrUnique(edits, expectedTable, actualTable, "PRIMARY_KEY",
                        "PRIMARY_KEY", "IS_MISSING", "PRIMARY_KEY", "IS_EXTRA", caseSensitive);
                compareForeignKeys(edits, expectedTable, actualTable, caseSensitive);
                comparePrimaryOrUnique(edits, expectedTable, actualTable, "UNIQUE",
                        "UNIQUE", "IS_MISSING", "UNIQUE", "IS_EXTRA", caseSensitive);
                compareChecks(edits, expectedTable, actualTable, caseSensitive);
                compareDefaults(edits, expectedTable, actualTable, caseSensitive);
            }
        }

        return edits;
    }

    private static void compareColumns(
            List<CreateSchemaEdit> edits,
            CreateSchemaGraph.TableNode expectedTable,
            CreateSchemaGraph.TableNode actualTable,
            boolean caseSensitive) {
        
        List<CreateSchemaGraph.ColumnNode> unmappedExpected = new ArrayList<>();
        List<CreateSchemaGraph.ColumnNode> unmappedActual = new ArrayList<>();
        java.util.Map<String, CreateSchemaGraph.ColumnNode> mappedColumns = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, CreateSchemaGraph.ColumnNode> expectedColumnEntry : expectedTable.columns().entrySet()) {
            String key = expectedColumnEntry.getKey();
            CreateSchemaGraph.ColumnNode actualColumn = actualTable.columns().get(key);
            if (actualColumn != null) {
                mappedColumns.put(key, actualColumn);
            } else {
                unmappedExpected.add(expectedColumnEntry.getValue());
            }
        }

        for (Map.Entry<String, CreateSchemaGraph.ColumnNode> actualColumnEntry : actualTable.columns().entrySet()) {
            if (!expectedTable.columns().containsKey(actualColumnEntry.getKey())) {
                unmappedActual.add(actualColumnEntry.getValue());
            }
        }

        if (unmappedExpected.size() == 1 && unmappedActual.size() == 1) {
            CreateSchemaGraph.ColumnNode exp = unmappedExpected.get(0);
            CreateSchemaGraph.ColumnNode act = unmappedActual.get(0);
            mappedColumns.put(CreateSchemaNames.normalizeIdentifier(exp.name(), caseSensitive), act);
            edits.add(edit("COLUMN", "NOT_EQUAL", expectedTable.name(), List.of(exp.name()), null, null, List.of(), exp.name(), act.name(), "Bảng " + expectedTable.name() + ": sai tên cột " + exp.name() + " thành " + act.name()));
            unmappedExpected.clear();
            unmappedActual.clear();
        } else {
            List<CreateSchemaGraph.ColumnNode> newlyMappedExp = new ArrayList<>();
            List<CreateSchemaGraph.ColumnNode> newlyMappedAct = new ArrayList<>();
            for (CreateSchemaGraph.ColumnNode exp : unmappedExpected) {
                CreateSchemaGraph.ColumnNode bestMatch = null;
                for (CreateSchemaGraph.ColumnNode act : unmappedActual) {
                    if (newlyMappedAct.contains(act)) continue;
                    String expFamily = CreateSchemaNames.extractTypeFamily(exp.normalizedType());
                    String actFamily = CreateSchemaNames.extractTypeFamily(act.normalizedType());
                    if (expFamily.equals(actFamily) && !expFamily.isBlank()) {
                        bestMatch = act;
                        break;
                    }
                }
                if (bestMatch != null) {
                    newlyMappedExp.add(exp);
                    newlyMappedAct.add(bestMatch);
                    mappedColumns.put(CreateSchemaNames.normalizeIdentifier(exp.name(), caseSensitive), bestMatch);
                    edits.add(edit("COLUMN", "NOT_EQUAL", expectedTable.name(), List.of(exp.name()), null, null, List.of(), exp.name(), bestMatch.name(), "Bảng " + expectedTable.name() + ": sai tên cột " + exp.name() + " thành " + bestMatch.name()));
                }
            }
            unmappedExpected.removeAll(newlyMappedExp);
            unmappedActual.removeAll(newlyMappedAct);
        }

        for (CreateSchemaGraph.ColumnNode exp : unmappedExpected) {
            edits.add(edit("COLUMN", "IS_MISSING", expectedTable.name(), List.of(exp.name()), null, null, List.of(), exp.name(), null, "Bảng " + expectedTable.name() + ": thiếu cột " + exp.name()));
        }

        for (CreateSchemaGraph.ColumnNode act : unmappedActual) {
            edits.add(edit("COLUMN", "IS_EXTRA", actualTable.name(), List.of(act.name()), null, null, List.of(), null, act.name(), "Bảng " + actualTable.name() + ": dư cột " + act.name()));
        }

        for (Map.Entry<String, CreateSchemaGraph.ColumnNode> entry : mappedColumns.entrySet()) {
            CreateSchemaGraph.ColumnNode expectedColumn = null;
            for(CreateSchemaGraph.ColumnNode c : expectedTable.columns().values()) {
                if(CreateSchemaNames.normalizeIdentifier(c.name(), caseSensitive).equals(entry.getKey())) {
                    expectedColumn = c; break;
                }
            }
            CreateSchemaGraph.ColumnNode actualColumn = entry.getValue();
            if (expectedColumn == null) continue;

            if (!expectedColumn.normalizedType().isBlank()) {
                String expectedFamily = CreateSchemaNames.extractTypeFamily(expectedColumn.normalizedType());
                String actualFamily = CreateSchemaNames.extractTypeFamily(actualColumn.normalizedType());
                
                if (!expectedFamily.equals(actualFamily)) {
                    edits.add(edit(
                            "DATA_TYPE", "FAMILY_MISMATCH",
                            expectedTable.name(),
                            List.of(expectedColumn.name()),
                            null,
                            null,
                            List.of(),
                            expectedFamily,
                            actualFamily,
                            String.format("Bảng %s: cột %s sai họ kiểu dữ liệu (kỳ vọng: %s, thực tế: %s)",
                                    expectedTable.name(), expectedColumn.name(), expectedFamily, actualFamily)));
                } else if (!expectedColumn.normalizedType().equals(actualColumn.normalizedType())) {
                    edits.add(edit(
                            "DATA_TYPE", "SIZE_MISMATCH",
                            expectedTable.name(),
                            List.of(expectedColumn.name()),
                            null,
                            null,
                            List.of(),
                            expectedColumn.rawType(),
                            actualColumn.rawType(),
                            String.format("Bảng %s: cột %s sai kích thước (kỳ vọng: %s, thực tế: %s)",
                                    expectedTable.name(), expectedColumn.name(), expectedColumn.rawType(), actualColumn.rawType())));
                }
            }

            if (expectedColumn.nullable() != null && !expectedColumn.nullable().equals(actualColumn.nullable())) {
                final String expectedColNameNorm = entry.getKey();
                boolean isExpectedPkColumn = expectedTable.constraints().stream()
                        .filter(c -> "PRIMARY_KEY".equalsIgnoreCase(c.type()))
                        .flatMap(c -> c.columns().stream())
                        .anyMatch(col -> CreateSchemaNames.normalizeIdentifier(col, caseSensitive)
                                .equals(expectedColNameNorm));

                boolean isCascadingNullability = isExpectedPkColumn 
                        && Boolean.FALSE.equals(expectedColumn.nullable()) 
                        && Boolean.TRUE.equals(actualColumn.nullable());

                if (!isCascadingNullability) {
                    edits.add(edit(
                            "NULLABILITY", "NOT_EQUAL",
                            expectedTable.name(),
                            List.of(expectedColumn.name()),
                            null,
                            null,
                            List.of(),
                            expectedColumn.nullable() ? "NULL" : "NOT NULL",
                            actualColumn.nullable() ? "NULL" : "NOT NULL",
                            String.format("Bảng %s: cột %s sai nullable", expectedTable.name(), expectedColumn.name())));
                }
            }

            if (expectedColumn.identity() != null && !expectedColumn.identity().equals(actualColumn.identity())) {
                edits.add(edit(
                        "IDENTITY", "NOT_EQUAL",
                        expectedTable.name(),
                        List.of(expectedColumn.name()),
                        null,
                        null,
                        List.of(),
                        expectedColumn.identity() ? "IDENTITY" : "NO IDENTITY",
                        actualColumn.identity() ? "IDENTITY" : "NO IDENTITY",
                        String.format("Bảng %s: cột %s sai IDENTITY", expectedTable.name(), expectedColumn.name())));
            }
        }
    }

    private static void comparePrimaryOrUnique(
            List<CreateSchemaEdit> edits,
            CreateSchemaGraph.TableNode expectedTable,
            CreateSchemaGraph.TableNode actualTable,
            String type,
            String missingTarget, String missingCondition,
            String extraTarget, String extraCondition,
            boolean caseSensitive) {
        List<CreateSchemaGraph.ConstraintNode> expectedConstraints = new ArrayList<>(constraintsOfType(expectedTable, type));
        List<CreateSchemaGraph.ConstraintNode> actualConstraints = new ArrayList<>(constraintsOfType(actualTable, type));

        expectedConstraints.removeIf(expected -> {
            for (int i = 0; i < actualConstraints.size(); i++) {
                if (columnsMatch(expected, actualConstraints.get(i), caseSensitive)) {
                    actualConstraints.remove(i);
                    return true;
                }
            }
            return false;
        });

        if ("PRIMARY_KEY".equals(type) && !expectedConstraints.isEmpty() && !actualConstraints.isEmpty()) {
            CreateSchemaGraph.ConstraintNode expectedConstraint = expectedConstraints.remove(0);
            CreateSchemaGraph.ConstraintNode actualConstraint = actualConstraints.remove(0);
            edits.add(edit(
                    missingTarget, "MISMATCH",
                    expectedTable.name(),
                    expectedConstraint.columns(),
                    type,
                    null,
                    List.of(),
                    String.join(",", expectedConstraint.columns()),
                    String.join(",", actualConstraint.columns()),
                    String.format("Bảng %s: %s sai cột (kỳ vọng: %s, thực tế: %s)",
                            expectedTable.name(), type, String.join(", ", expectedConstraint.columns()), String.join(", ", actualConstraint.columns()))));
        }

        for (CreateSchemaGraph.ConstraintNode expectedConstraint : expectedConstraints) {
            edits.add(edit(
                    missingTarget, missingCondition,
                    expectedTable.name(),
                    expectedConstraint.columns(),
                    type,
                    null,
                    List.of(),
                    String.join(",", expectedConstraint.columns()),
                    null,
                    String.format("Bảng %s: thiếu %s trên %s",
                            expectedTable.name(), type, String.join(", ", expectedConstraint.columns()))));
        }

        for (CreateSchemaGraph.ConstraintNode actualConstraint : actualConstraints) {
            edits.add(edit(
                    extraTarget, extraCondition,
                    actualTable.name(),
                    actualConstraint.columns(),
                    type,
                    null,
                    List.of(),
                    null,
                    String.join(",", actualConstraint.columns()),
                    String.format("Bảng %s: dư %s trên %s",
                            actualTable.name(), type, String.join(", ", actualConstraint.columns()))));
        }
    }

    private static void compareForeignKeys(
            List<CreateSchemaEdit> edits,
            CreateSchemaGraph.TableNode expectedTable,
            CreateSchemaGraph.TableNode actualTable,
            boolean caseSensitive) {
        List<CreateSchemaGraph.ConstraintNode> expectedForeignKeys = new ArrayList<>(constraintsOfType(expectedTable, "FOREIGN_KEY"));
        List<CreateSchemaGraph.ConstraintNode> actualForeignKeys = new ArrayList<>(constraintsOfType(actualTable, "FOREIGN_KEY"));

        expectedForeignKeys.removeIf(expected -> {
            for (int i = 0; i < actualForeignKeys.size(); i++) {
                if (foreignKeyMatches(expected, actualForeignKeys.get(i), caseSensitive)) {
                    actualForeignKeys.remove(i);
                    return true;
                }
            }
            return false;
        });

        for (int i = 0; i < expectedForeignKeys.size(); i++) {
            CreateSchemaGraph.ConstraintNode expected = expectedForeignKeys.get(i);
            int matchIndex = -1;
            for (int j = 0; j < actualForeignKeys.size(); j++) {
                CreateSchemaGraph.ConstraintNode actual = actualForeignKeys.get(j);
                boolean sameReferencedTable = CreateSchemaNames.normalizeIdentifier(expected.referencedTable(), caseSensitive)
                        .equals(CreateSchemaNames.normalizeIdentifier(actual.referencedTable(), caseSensitive));
                boolean hasCommonColumns = expected.columns().stream()
                        .anyMatch(c -> CreateSchemaNames.normalizeIdentifierList(actual.columns(), caseSensitive)
                                .contains(CreateSchemaNames.normalizeIdentifier(c, caseSensitive)));

                if (sameReferencedTable || hasCommonColumns) {
                    matchIndex = j;
                    break;
                }
            }

            if (matchIndex >= 0) {
                CreateSchemaGraph.ConstraintNode actual = actualForeignKeys.remove(matchIndex);
                edits.add(edit(
                        "FOREIGN_KEY", "MISMATCH",
                        expectedTable.name(),
                        expected.columns(),
                        "FOREIGN_KEY",
                        expected.referencedTable(),
                        expected.referencedColumns(),
                        referenceLabel(expected),
                        referenceLabel(actual),
                        String.format("Bảng %s: khóa ngoại %s sai lệch tham chiếu",
                                expectedTable.name(), String.join(", ", expected.columns()))));
                expectedForeignKeys.remove(i);
                i--;
            }
        }

        for (CreateSchemaGraph.ConstraintNode expected : expectedForeignKeys) {
            edits.add(edit(
                    "FOREIGN_KEY", "IS_MISSING",
                    expectedTable.name(),
                    expected.columns(),
                    "FOREIGN_KEY",
                    expected.referencedTable(),
                    expected.referencedColumns(),
                    referenceLabel(expected),
                    null,
                    String.format("Bảng %s: thiếu khóa ngoại %s -> %s",
                            expectedTable.name(), String.join(", ", expected.columns()),
                            referenceLabel(expected))));
        }

        for (CreateSchemaGraph.ConstraintNode actual : actualForeignKeys) {
            edits.add(edit(
                    "FOREIGN_KEY", "IS_EXTRA",
                    actualTable.name(),
                    actual.columns(),
                    "FOREIGN_KEY",
                    actual.referencedTable(),
                    actual.referencedColumns(),
                    null,
                    referenceLabel(actual),
                    String.format("Bảng %s: dư khóa ngoại %s -> %s",
                            actualTable.name(), String.join(", ", actual.columns()),
                            referenceLabel(actual))));
        }
    }

    private static void compareChecks(
            List<CreateSchemaEdit> edits,
            CreateSchemaGraph.TableNode expectedTable,
            CreateSchemaGraph.TableNode actualTable,
            boolean caseSensitive) {
        compareExpressionConstraint(
                edits,
                expectedTable,
                actualTable,
                "CHECK",
                "CHECK", "IS_MISSING",
                "CHECK", "IS_EXTRA",
                "CHECK", "EXPRESSION_MISMATCH",
                caseSensitive);
    }

    private static void compareDefaults(
            List<CreateSchemaEdit> edits,
            CreateSchemaGraph.TableNode expectedTable,
            CreateSchemaGraph.TableNode actualTable,
            boolean caseSensitive) {
        compareExpressionConstraint(
                edits,
                expectedTable,
                actualTable,
                "DEFAULT",
                "DEFAULT", "IS_MISSING",
                "DEFAULT", "IS_EXTRA",
                "DEFAULT", "VALUE_MISMATCH",
                caseSensitive);
    }

    private static void compareExpressionConstraint(
            List<CreateSchemaEdit> edits,
            CreateSchemaGraph.TableNode expectedTable,
            CreateSchemaGraph.TableNode actualTable,
            String type,
            String missingTarget, String missingCondition,
            String extraTarget, String extraCondition,
            String mismatchTarget, String mismatchCondition,
            boolean caseSensitive) {
        List<CreateSchemaGraph.ConstraintNode> expectedConstraints = constraintsOfType(expectedTable, type);
        List<CreateSchemaGraph.ConstraintNode> actualConstraints = constraintsOfType(actualTable, type);

        for (CreateSchemaGraph.ConstraintNode expectedConstraint : expectedConstraints) {
            CreateSchemaGraph.ConstraintNode actualConstraint = findExpressionConstraint(
                    expectedConstraint,
                    actualConstraints,
                    type,
                    caseSensitive);
            if (actualConstraint == null) {
                edits.add(edit(
                        missingTarget, missingCondition,
                        expectedTable.name(),
                        expectedConstraint.columns(),
                        type,
                        null,
                        List.of(),
                        expressionValue(expectedConstraint, type),
                        null,
                        String.format("Bảng %s: thiếu %s trên %s",
                                expectedTable.name(), type, String.join(", ", expectedConstraint.columns()))));
                continue;
            }

            String expectedExpression = CreateSchemaNames.normalizeExpression(expressionValue(expectedConstraint, type));
            String actualExpression = CreateSchemaNames.normalizeExpression(expressionValue(actualConstraint, type));
            if (!expectedExpression.isBlank() && !expectedExpression.equals(actualExpression)) {
                edits.add(edit(
                        mismatchTarget, mismatchCondition,
                        expectedTable.name(),
                        expectedConstraint.columns(),
                        type,
                        null,
                        List.of(),
                        expressionValue(expectedConstraint, type),
                        expressionValue(actualConstraint, type),
                        String.format("Bảng %s: %s trên %s sai biểu thức/giá trị",
                                expectedTable.name(), type, String.join(", ", expectedConstraint.columns()))));
            }
        }

        for (CreateSchemaGraph.ConstraintNode actualConstraint : actualConstraints) {
            if (expectedConstraints.stream().noneMatch(expectedConstraint ->
                    findExpressionConstraint(expectedConstraint, List.of(actualConstraint), type, caseSensitive) != null)) {
                edits.add(edit(
                        extraTarget, extraCondition,
                        actualTable.name(),
                        actualConstraint.columns(),
                        type,
                        null,
                        List.of(),
                        null,
                        expressionValue(actualConstraint, type),
                        String.format("Bảng %s: dư %s trên %s",
                                actualTable.name(), type, String.join(", ", actualConstraint.columns()))));
            }
        }
    }

    private static CreateSchemaGraph.ConstraintNode findExpressionConstraint(
            CreateSchemaGraph.ConstraintNode expectedConstraint,
            List<CreateSchemaGraph.ConstraintNode> actualConstraints,
            String type,
            boolean caseSensitive) {
        for (CreateSchemaGraph.ConstraintNode actualConstraint : actualConstraints) {
            if (columnsMatch(expectedConstraint, actualConstraint, caseSensitive)) {
                return actualConstraint;
            }
        }

        if (!"CHECK".equalsIgnoreCase(type)) {
            return null;
        }

        String expectedExpression = CreateSchemaNames.normalizeExpression(expressionValue(expectedConstraint, type));
        if (expectedExpression.isBlank()) {
            return null;
        }

        return actualConstraints.stream()
                .filter(actualConstraint -> expectedExpression.equals(
                        CreateSchemaNames.normalizeExpression(expressionValue(actualConstraint, type))))
                .findFirst()
                .orElse(null);
    }

    private static List<CreateSchemaGraph.ConstraintNode> constraintsOfType(
            CreateSchemaGraph.TableNode table,
            String type) {
        return table.constraints().stream()
                .filter(constraint -> constraint.isType(type))
                .toList();
    }

    private static boolean columnsMatch(
            CreateSchemaGraph.ConstraintNode expected,
            CreateSchemaGraph.ConstraintNode actual,
            boolean caseSensitive) {
        return CreateSchemaNames.normalizeIdentifierList(expected.columns(), caseSensitive)
                .equals(CreateSchemaNames.normalizeIdentifierList(actual.columns(), caseSensitive));
    }

    private static boolean foreignKeyMatches(
            CreateSchemaGraph.ConstraintNode expected,
            CreateSchemaGraph.ConstraintNode actual,
            boolean caseSensitive) {
        return columnsMatch(expected, actual, caseSensitive)
                && CreateSchemaNames.normalizeIdentifier(expected.referencedTable(), caseSensitive)
                .equals(CreateSchemaNames.normalizeIdentifier(actual.referencedTable(), caseSensitive))
                && CreateSchemaNames.normalizeIdentifierList(expected.referencedColumns(), caseSensitive)
                .equals(CreateSchemaNames.normalizeIdentifierList(actual.referencedColumns(), caseSensitive));
    }

    private static String referenceLabel(CreateSchemaGraph.ConstraintNode foreignKey) {
        if (foreignKey == null) {
            return "";
        }
        return (foreignKey.referencedTable() == null ? "" : foreignKey.referencedTable())
                + "(" + String.join(", ", foreignKey.referencedColumns()) + ")";
    }

    private static String expressionValue(CreateSchemaGraph.ConstraintNode constraint, String type) {
        return "DEFAULT".equalsIgnoreCase(type) ? constraint.defaultValue() : constraint.expression();
    }

    private static CreateSchemaEdit edit(
            String target,
            String condition,
            String table,
            List<String> columns,
            String constraintType,
            String referencedTable,
            List<String> referencedColumns,
            String expected,
            String actual,
            String message) {
        return new CreateSchemaEdit(
                target,
                condition,
                table,
                columns == null ? List.of() : List.copyOf(columns),
                constraintType,
                referencedTable,
                referencedColumns == null ? List.of() : List.copyOf(referencedColumns),
                expected,
                actual,
                message);
    }
}

