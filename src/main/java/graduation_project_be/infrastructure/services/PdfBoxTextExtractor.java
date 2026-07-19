package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.PdfTextExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class PdfBoxTextExtractor implements PdfTextExtractor {

    @Override
    public String extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return "";
        }

        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return new PDFTextStripper().getText(document).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Không thể đọc nội dung PDF", e);
        }
    }
}
