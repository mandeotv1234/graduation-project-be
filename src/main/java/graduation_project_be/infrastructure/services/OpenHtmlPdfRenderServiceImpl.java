package graduation_project_be.infrastructure.services;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import graduation_project_be.application.port.services.PdfRenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Renders Thymeleaf templates to PDF using OpenHTMLtoPDF + PDFBox.
 * Liberation Serif fonts are registered for correct Vietnamese diacritics rendering.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenHtmlPdfRenderServiceImpl implements PdfRenderService {

    private final TemplateEngine templateEngine;

    @Override
    public byte[] render(String templateName, Map<String, Object> model) {
        Context ctx = new Context(Locale.of("vi", "VN"));
        if (model != null) {
            model.forEach(ctx::setVariable);
        }

        String html = templateEngine.process(templateName, ctx);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, "classpath:/");

            // Register all 4 Liberation Serif variants for complete font coverage
            builder.useFont(() -> fontStream("LiberationSerif-Regular.ttf"),
                    "Liberation Serif", 400, PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> fontStream("LiberationSerif-Bold.ttf"),
                    "Liberation Serif", 700, PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(() -> fontStream("LiberationSerif-Italic.ttf"),
                    "Liberation Serif", 400, PdfRendererBuilder.FontStyle.ITALIC, true);
            builder.useFont(() -> fontStream("LiberationSerif-BoldItalic.ttf"),
                    "Liberation Serif", 700, PdfRendererBuilder.FontStyle.ITALIC, true);

            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("PDF rendering failed for template '{}': {}", templateName, e.getMessage(), e);
            throw new RuntimeException("Failed to render PDF from template: " + templateName, e);
        }
    }

    private InputStream fontStream(String fileName) {
        InputStream stream = getClass().getResourceAsStream("/fonts/" + fileName);
        if (stream == null) {
            throw new RuntimeException("Font file not found in classpath: /fonts/" + fileName);
        }
        return stream;
    }
}
