package graduation_project_be.shared.utils;

import java.text.Normalizer;

/** Utility for converting strings to URL-safe slugs. */
public final class SlugUtil {

    private SlugUtil() {
    }

    /**
     * Convert an arbitrary string to a URL-safe slug.
     * Strips Vietnamese diacritics, lowercases, replaces non-alphanumeric chars
     * with hyphens, and trims leading/trailing hyphens.
     * Result is truncated to 60 characters to avoid overly long filenames.
     */
    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "exam";
        }

        // NFD normalization separates base chars from combining marks
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        // Remove combining diacritical marks
        String stripped = normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        // Lowercase and replace non-alphanumeric sequences with hyphen
        String slug = stripped.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");

        if (slug.isEmpty()) {
            return "exam";
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }
}
