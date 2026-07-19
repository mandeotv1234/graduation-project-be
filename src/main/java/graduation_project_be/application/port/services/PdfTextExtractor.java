package graduation_project_be.application.port.services;

public interface PdfTextExtractor {
    String extract(byte[] pdfBytes);
}
