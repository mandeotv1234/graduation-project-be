package graduation_project_be.application.usecases.grading.createtable;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

final class CreateSchemaNames {

    private CreateSchemaNames() {
    }

    static String normalizeIdentifier(String value, boolean caseSensitive) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return caseSensitive ? normalized : normalized.toLowerCase(Locale.ROOT);
    }

    static String normalizeIdentifierList(List<String> values, boolean caseSensitive) {
        if (values == null) {
            return "";
        }
        return values.stream()
                .map(value -> normalizeIdentifier(value, caseSensitive))
                .collect(Collectors.joining(","));
    }

    static String normalizeSqlType(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    static String normalizeExpression(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        boolean changed = true;
        while (changed && normalized.startsWith("(") && normalized.endsWith(")") && normalized.length() > 1) {
            changed = false;
            int depth = 0;
            boolean wraps = true;
            for (int i = 0; i < normalized.length(); i++) {
                char ch = normalized.charAt(i);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0 && i < normalized.length() - 1) {
                        wraps = false;
                        break;
                    }
                }
                if (depth < 0) {
                    wraps = false;
                    break;
                }
            }
            if (wraps) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
                changed = true;
            }
        }
        return normalized
                .replaceAll("[\\[\\]]", "")
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    static String extractTypeFamily(String normalizedType) {
        return extractTypeFamily(normalizedType, false, null);
    }

    static String extractTypeFamily(String normalizedType, boolean checkDataTypeFamily, java.util.Map<String, String> customTypeFamilies) {
        if (normalizedType == null) {
            return "";
        }
        String baseType;
        int parenIndex = normalizedType.indexOf('(');
        if (parenIndex > 0) {
            baseType = normalizedType.substring(0, parenIndex).trim();
        } else {
            baseType = normalizedType.trim();
        }

        if (!checkDataTypeFamily) {
            return baseType;
        }

        int spaceIndex = baseType.indexOf(' ');
        if (spaceIndex > 0) {
            baseType = baseType.substring(0, spaceIndex).trim();
        }

        if (customTypeFamilies != null && !customTypeFamilies.isEmpty()) {
            return customTypeFamilies.getOrDefault(baseType, baseType);
        }

        switch (baseType) {
            case "VARCHAR":
            case "NVARCHAR":
            case "CHAR":
            case "NCHAR":
            case "TEXT":
            case "NTEXT":
                return "STRING_FAMILY";
            case "FLOAT":
            case "DOUBLE":
            case "REAL":
            case "DECIMAL":
            case "NUMERIC":
            case "MONEY":
            case "SMALLMONEY":
                return "NUMERIC_FAMILY";
            case "INT":
            case "INTEGER":
            case "BIGINT":
            case "SMALLINT":
            case "TINYINT":
                return "INT_FAMILY";
            case "DATE":
            case "DATETIME":
            case "DATETIME2":
            case "SMALLDATETIME":
            case "TIME":
            case "TIMESTAMP":
                return "DATETIME_FAMILY";
            default:
                return baseType;
        }
    }

    static String extractTypeLength(String normalizedType) {
        if (normalizedType == null) {
            return "";
        }
        int start = normalizedType.indexOf('(');
        if (start < 0) return "";
        int end = normalizedType.lastIndexOf(')');
        if (end > start) return normalizedType.substring(start + 1, end).trim();
        return "";
    }
}
