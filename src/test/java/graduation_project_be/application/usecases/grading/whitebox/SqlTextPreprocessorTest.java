package graduation_project_be.application.usecases.grading.whitebox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for SqlTextPreprocessor: removes comments, quotes strings, neutralizes bracket identifiers.
 */
class SqlTextPreprocessorTest {

    @Test
    void clean_blockComment() {
        String sql = "SELECT /* comment */ * FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT   * FROM a", result);
    }

    @Test
    void clean_blockCommentMultiline() {
        String sql = "SELECT /* multi\nline\ncomment */ * FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT   * FROM a", result);
    }

    @Test
    void clean_blockCommentNoSpace() {
        String sql = "SELECT/*comment*/FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT FROM a", result);
    }

    @Test
    void clean_nestedBlockComments() {
        String sql = "SELECT /* outer /* inner */ still comment */ * FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        // Regex is non-greedy (minimal match), so /* outer /* inner */ matches first,
        // leaving "still comment */" in the result (it's not a valid comment anymore)
        assertEquals("SELECT   still comment */ * FROM a", result);
    }

    @Test
    void clean_lineComment() {
        String sql = "SELECT * -- this is a comment\nFROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT *  \nFROM a", result);
    }

    @Test
    void clean_lineCommentEndOfLine() {
        String sql = "SELECT * FROM a -- comment at end";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT * FROM a  ", result);
    }

    @Test
    void clean_multipleLineComments() {
        String sql = "SELECT -- comment1\n* -- comment2\nFROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT  \n*  \nFROM a", result);
    }

    @Test
    void clean_stringLiteralSingleQuote() {
        String sql = "SELECT 'hello' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT '' FROM a", result);
    }

    @Test
    void clean_stringWithEscapedQuote() {
        String sql = "SELECT 'it''s' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT '' FROM a", result);
    }

    @Test
    void clean_stringPreservesKeyword() {
        // Keywords inside strings should be replaced with empty quotes, not detected
        String sql = "SELECT 'JOIN' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT '' FROM a", result);
        // Now 'JOIN' is gone, not a bare token
    }

    @Test
    void clean_nStringLiteral() {
        String sql = "SELECT N'hello' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT '' FROM a", result);
    }

    @Test
    void clean_nStringUppercase() {
        String sql = "SELECT N'data' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT '' FROM a", result);
    }

    @Test
    void clean_stringWithComma() {
        String sql = "SELECT 'a,b,c' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT '' FROM a", result);
    }

    @Test
    void clean_multipleStrings() {
        String sql = "SELECT 'first' || 'second' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT '' || '' FROM a", result);
    }

    @Test
    void clean_bracketIdentifier() {
        String sql = "SELECT [column name] FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT [id] FROM a", result);
    }

    @Test
    void clean_bracketIdentifierWithSpecialChars() {
        String sql = "SELECT [col-name!] FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT [id] FROM a", result);
    }

    @Test
    void clean_bracketIdentifierPreservesKeyword() {
        // [SELECT] column literally named SELECT should become [id], not matched as keyword
        String sql = "SELECT [SELECT] FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT [id] FROM a", result);
    }

    @Test
    void clean_multipleBracketIdentifiers() {
        String sql = "SELECT [col1], [col 2] FROM [my table]";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT [id], [id] FROM [id]", result);
    }

    @Test
    void clean_combined_commentAndString() {
        String sql = "SELECT /* comment */ 'string' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT   '' FROM a", result);
    }

    @Test
    void clean_combined_allThree() {
        String sql = "SELECT -- line comment\n 'string' /* block */ [id col] FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        // Line comment leaves a space, string becomes '', bracket becomes [id]
        assertEquals("SELECT  \n ''   [id] FROM a", result);
    }

    @Test
    void clean_nullInput() {
        String result = SqlTextPreprocessor.clean(null);
        assertEquals("", result);
    }

    @Test
    void clean_emptyString() {
        String result = SqlTextPreprocessor.clean("");
        assertEquals("", result);
    }

    @Test
    void clean_noCleanup() {
        String sql = "SELECT * FROM a WHERE id = 1";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT * FROM a WHERE id = 1", result);
    }

    @Test
    void clean_selectStar_bareKeyword() {
        // SELECT * (no comment, no string, bare keyword)
        String sql = "SELECT * FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT * FROM a", result);
    }

    @Test
    void clean_selectStarInsideString() {
        String sql = "SELECT 'SELECT * FROM' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT '' FROM a", result);
    }

    @Test
    void clean_unclosedString() {
        // The regex should NOT match unclosed strings (requires closing quote)
        String sql = "SELECT 'unclosed";
        String result = SqlTextPreprocessor.clean(sql);
        // Regex expects [^'] or '' between quotes, so unclosed is left as-is
        assertEquals("SELECT 'unclosed", result);
    }

    @Test
    void clean_unclosedComment() {
        String sql = "SELECT * /* unclosed comment";
        String result = SqlTextPreprocessor.clean(sql);
        // Regex expects */ to close, so unclosed is left as-is
        assertEquals("SELECT * /* unclosed comment", result);
    }

    @Test
    void clean_stringContainsBlockCommentLiteral() {
        // String contains the text /* but it's inside quotes, so not treated as comment
        String sql = "SELECT '/* not a comment */' FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT '' FROM a", result);
    }

    @Test
    void clean_commentContainsStringLiteral() {
        // Comment contains quote, but order matters: comments processed first
        String sql = "SELECT /* 'in comment' */ * FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        assertEquals("SELECT   * FROM a", result);
    }

    @Test
    void clean_orderOfOperations() {
        // Block comment, then line comment, then strings, then brackets
        String sql = "SELECT /* block */\n-- line\n'string' [bracket] FROM a";
        String result = SqlTextPreprocessor.clean(sql);
        // Block comment becomes space, line comment becomes space, string becomes '', bracket becomes [id]
        assertEquals("SELECT  \n \n'' [id] FROM a", result);
    }
}
