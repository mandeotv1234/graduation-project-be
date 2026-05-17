package graduation_project_be.infrastructure.utils;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * Sanitizes untrusted HTML from teacher-entered question content.
 * Strips scripts, iframes, and on* event handlers; keeps basic formatting tags.
 */
public final class HtmlSanitizer {

    private static final Safelist SAFELIST = Safelist.basic()
            .addTags("code", "pre", "u");

    private HtmlSanitizer() {}

    /**
     * Clean input HTML, retaining only safe formatting tags.
     * Returns empty string for null input.
     */
    public static String clean(String html) {
        if (html == null) return "";
        return Jsoup.clean(html, SAFELIST);
    }
}
