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
        if (normalizedType == null) {
            return "";
        }
        int parenIndex = normalizedType.indexOf('(');
        if (parenIndex > 0) {
            return normalizedType.substring(0, parenIndex).trim();
        }
        return normalizedType.trim();
    }
}
