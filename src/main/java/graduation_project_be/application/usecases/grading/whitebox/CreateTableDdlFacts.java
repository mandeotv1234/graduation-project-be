package graduation_project_be.application.usecases.grading.whitebox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight SQL Server DDL facts for the CREATE_TABLE rules in whitebox-testing-plan.html.
 */
final class CreateTableDdlFacts {

    private static final String IDENTIFIER = "(?:\\[[^\\]]+\\]|[A-Za-z_][\\w$#@]*)";
    private static final String QUALIFIED_IDENTIFIER = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")*";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)\\bCREATE\\s+TABLE\\s+(" + QUALIFIED_IDENTIFIER + ")\\s*\\(");
    private static final Pattern REFERENCES = Pattern.compile(
            "(?i)\\bREFERENCES\\s+(" + QUALIFIED_IDENTIFIER + ")");
    private static final Pattern NAMED_CONSTRAINT = Pattern.compile(
            "(?i)\\bCONSTRAINT\\s+" + IDENTIFIER + "\\s+"
                    + "(PRIMARY\\s+KEY|FOREIGN\\s+KEY|UNIQUE|CHECK)\\b");
    private static final Pattern CONSTRAINT_KEYWORD = Pattern.compile(
            "(?i)\\b(PRIMARY\\s+KEY|FOREIGN\\s+KEY|UNIQUE|CHECK)\\b");
    private static final Pattern DEPRECATED_TYPE = Pattern.compile("(?i)^(TEXT|NTEXT|IMAGE)\\b");

    private final String sql;
    private final List<TableBlock> tables;

    private CreateTableDdlFacts(String sql, List<TableBlock> tables) {
        this.sql = sql;
        this.tables = tables;
    }

    static CreateTableDdlFacts analyze(String rawSql) {
        String sql = SqlTextPreprocessor.cleanPreservingIdentifiers(rawSql);
        List<TableBlock> tables = new ArrayList<>();
        Matcher matcher = CREATE_TABLE.matcher(sql);
        int searchFrom = 0;
        while (matcher.find(searchFrom)) {
            int openParen = matcher.end() - 1;
            int closeParen = findMatchingParen(sql, openParen);
            if (closeParen < 0) {
                break;
            }
            String tableName = normalizeIdentifier(matcher.group(1));
            String body = sql.substring(openParen + 1, closeParen);
            tables.add(TableBlock.parse(tableName, body));
            searchFrom = closeParen + 1;
        }
        return new CreateTableDdlFacts(sql, List.copyOf(tables));
    }

    boolean everyTableHasPrimaryKey() {
        return !tables.isEmpty() && tables.stream().allMatch(TableBlock::hasPrimaryKey);
    }

    List<String> tablesMissingPrimaryKey() {
        return tables.stream().filter(table -> !table.hasPrimaryKey()).map(TableBlock::name).toList();
    }

    boolean allRelevantConstraintsAreNamed() {
        Matcher keywordMatcher = CONSTRAINT_KEYWORD.matcher(sql);
        while (keywordMatcher.find()) {
            int statementStart = Math.max(
                    Math.max(sql.lastIndexOf(',', keywordMatcher.start()), sql.lastIndexOf('(', keywordMatcher.start())),
                    sql.lastIndexOf(';', keywordMatcher.start()));
            String definitionPrefix = sql.substring(statementStart + 1, keywordMatcher.end());
            Matcher namedMatcher = NAMED_CONSTRAINT.matcher(definitionPrefix);
            boolean named = false;
            while (namedMatcher.find()) {
                if (namedMatcher.end() == definitionPrefix.length()) {
                    named = true;
                }
            }
            if (!named) {
                return false;
            }
        }
        return true;
    }

    boolean referencesAll(List<String> referencedTables) {
        Set<String> actual = new LinkedHashSet<>();
        Matcher matcher = REFERENCES.matcher(sql);
        while (matcher.find()) {
            actual.add(simpleName(matcher.group(1)));
        }
        if (referencedTables.isEmpty()) {
            return !actual.isEmpty();
        }
        return referencedTables.stream()
                .map(CreateTableDdlFacts::simpleName)
                .allMatch(actual::contains);
    }

    boolean columnsAllHave(List<String> columns, ColumnRequirement requirement) {
        if (columns.isEmpty()) {
            return false;
        }
        Map<String, String> definitions = new LinkedHashMap<>();
        tables.forEach(table -> definitions.putAll(table.columnDefinitions()));
        for (String column : columns) {
            String definition = definitions.get(simpleName(column));
            if (definition == null || !requirement.matches(definition)) {
                return false;
            }
        }
        return true;
    }

    boolean constraintsCoverColumns(List<String> columns, Pattern keyword) {
        if (columns.isEmpty()) {
            return false;
        }
        for (String column : columns) {
            String normalizedColumn = simpleName(column);
            boolean matched = tables.stream().anyMatch(table ->
                    table.definitions().stream().anyMatch(definition ->
                            keyword.matcher(definition).find()
                                    && containsIdentifier(definition, normalizedColumn)));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    boolean hasIdentity() {
        return Pattern.compile("(?i)\\bIDENTITY(?:\\s*\\(|\\b)").matcher(sql).find();
    }

    String firstDeprecatedType() {
        for (TableBlock table : tables) {
            for (String definition : table.columnDefinitions().values()) {
                String withoutName = definition.replaceFirst("^\\s*" + IDENTIFIER + "\\s+", "");
                Matcher matcher = DEPRECATED_TYPE.matcher(withoutName);
                if (matcher.find()) {
                    return matcher.group(1).toUpperCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    boolean contains(Pattern pattern) {
        return pattern.matcher(sql).find();
    }

    private static boolean containsIdentifier(String text, String identifier) {
        Matcher matcher = Pattern.compile(IDENTIFIER).matcher(text);
        while (matcher.find()) {
            if (simpleName(matcher.group()).equals(identifier)) {
                return true;
            }
        }
        return false;
    }

    private static int findMatchingParen(String sql, int openParen) {
        int depth = 0;
        for (int i = openParen; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            } else if (ch == ',' && depth == 0) {
                parts.add(body.substring(start, i).trim());
                start = i + 1;
            }
        }
        String tail = body.substring(start).trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private static String normalizeIdentifier(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").replace("[", "").replace("]", "");
    }

    private static String simpleName(String value) {
        String normalized = normalizeIdentifier(value);
        int dot = normalized.lastIndexOf('.');
        String simple = dot >= 0 ? normalized.substring(dot + 1) : normalized;
        return simple.toUpperCase(Locale.ROOT);
    }

    @FunctionalInterface
    interface ColumnRequirement {
        boolean matches(String definition);
    }

    private record TableBlock(
            String name,
            List<String> definitions,
            Map<String, String> columnDefinitions,
            boolean hasPrimaryKey) {

        static TableBlock parse(String name, String body) {
            List<String> definitions = splitTopLevel(body);
            Map<String, String> columns = new LinkedHashMap<>();
            boolean hasPrimaryKey = false;
            for (String definition : definitions) {
                if (Pattern.compile("(?i)\\bPRIMARY\\s+KEY\\b").matcher(definition).find()) {
                    hasPrimaryKey = true;
                }
                Matcher firstToken = Pattern.compile("^\\s*(" + IDENTIFIER + ")").matcher(definition);
                if (!firstToken.find()) {
                    continue;
                }
                String token = firstToken.group(1);
                if (token.equalsIgnoreCase("CONSTRAINT")
                        || token.equalsIgnoreCase("PRIMARY")
                        || token.equalsIgnoreCase("FOREIGN")
                        || token.equalsIgnoreCase("UNIQUE")
                        || token.equalsIgnoreCase("CHECK")) {
                    continue;
                }
                columns.put(simpleName(token), definition);
            }
            return new TableBlock(name, List.copyOf(definitions), Map.copyOf(columns), hasPrimaryKey);
        }
    }
}
