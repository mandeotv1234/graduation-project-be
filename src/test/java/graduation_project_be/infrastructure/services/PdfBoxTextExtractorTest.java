package graduation_project_be.infrastructure.services;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class PdfBoxTextExtractorTest {

    private final PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();

    @Test
    void extractReadsTextFromEveryPage() throws IOException {
        byte[] pdfBytes;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addPage(document, "PAGE ONE TABLE LEHOI");
            addPage(document, "PAGE TWO TABLE TIETMUC");
            document.save(output);
            pdfBytes = output.toByteArray();
        }

        assertThat(extractor.extract(pdfBytes))
                .contains("PAGE ONE TABLE LEHOI")
                .contains("PAGE TWO TABLE TIETMUC");
    }

    private void addPage(PDDocument document, String text) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(PDType1Font.HELVETICA, 12);
            content.newLineAtOffset(72, 720);
            content.showText(text);
            content.endText();
        }
    }
}
