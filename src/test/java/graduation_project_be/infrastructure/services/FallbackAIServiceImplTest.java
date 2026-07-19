package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.AIService.GeneratedQuestion;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FallbackAIServiceImplTest {

    private final CodexServiceImpl codexService = mock(CodexServiceImpl.class);
    private final ClaudeServiceImpl claudeService = mock(ClaudeServiceImpl.class);
    private final OpenAiServiceImpl openAiService = mock(OpenAiServiceImpl.class);
    private final FallbackAIServiceImpl fallbackService =
            new FallbackAIServiceImpl(codexService, claudeService, openAiService);

    @Test
    void generateSqlAnswer_usesCodexFirst() {
        GeneratedQuestion codexResult = new GeneratedQuestion("SELECT 1", null);
        when(codexService.generateSqlAnswer("question", "SELECT", "schema"))
                .thenReturn(codexResult);

        GeneratedQuestion result = fallbackService.generateSqlAnswer("question", "SELECT", "schema");

        assertEquals(codexResult, result);
        verify(claudeService, never()).generateSqlAnswer("question", "SELECT", "schema");
        verify(openAiService, never()).generateSqlAnswer("question", "SELECT", "schema");
    }

    @Test
    void generateSqlAnswer_usesOpenAiOnlyAfterCodexAndClaudeFail() {
        GeneratedQuestion openAiResult = new GeneratedQuestion("SELECT 1", null);
        when(codexService.generateSqlAnswer("question", "SELECT", "schema"))
                .thenReturn(null);
        when(claudeService.generateSqlAnswer("question", "SELECT", "schema"))
                .thenReturn(new GeneratedQuestion("-- AI generation failed", null));
        when(openAiService.generateSqlAnswer("question", "SELECT", "schema"))
                .thenReturn(openAiResult);

        GeneratedQuestion result = fallbackService.generateSqlAnswer("question", "SELECT", "schema");

        assertEquals(openAiResult, result);
        InOrder providerOrder = inOrder(codexService, claudeService, openAiService);
        providerOrder.verify(codexService).generateSqlAnswer("question", "SELECT", "schema");
        providerOrder.verify(claudeService).generateSqlAnswer("question", "SELECT", "schema");
        providerOrder.verify(openAiService).generateSqlAnswer("question", "SELECT", "schema");
    }

    @Test
    void generateSqlAnswer_returnsEmptyResultWhenAllProvidersFail() {
        when(codexService.generateSqlAnswer("question", "SELECT", "schema"))
                .thenReturn(null);
        when(claudeService.generateSqlAnswer("question", "SELECT", "schema"))
                .thenReturn(null);
        when(openAiService.generateSqlAnswer("question", "SELECT", "schema"))
                .thenReturn(null);

        GeneratedQuestion result = fallbackService.generateSqlAnswer("question", "SELECT", "schema");

        assertNull(result.correctQuery());
        assertNull(result.verifyScript());
    }
}
