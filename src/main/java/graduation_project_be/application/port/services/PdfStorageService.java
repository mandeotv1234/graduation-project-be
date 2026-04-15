package graduation_project_be.application.port.services;

import org.springframework.web.multipart.MultipartFile;

public interface PdfStorageService {
    String savePdf(MultipartFile file);
    byte[] loadPdf(String filePath);
    void deletePdf(String filePath);
}
