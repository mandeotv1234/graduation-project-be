package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.PdfStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class PdfStorageServiceImpl implements PdfStorageService {

    private final Path uploadDir;

    public PdfStorageServiceImpl(@Value("${app.upload.pdf-dir:uploads/exams/pdf}") String pdfDir) {
        this.uploadDir = Paths.get(pdfDir).toAbsolutePath();
    }

    @Override
    public String savePdf(MultipartFile file) {
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            String uniqueFileName = String.format("exam_%d_%s.pdf",
                    System.currentTimeMillis(),
                    UUID.randomUUID().toString().replace("-", "").substring(0, 8));

            Path filePath = uploadDir.resolve(uniqueFileName);
            Files.write(filePath, file.getBytes());

            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save PDF file", e);
        }
    }

    @Override
    public byte[] loadPdf(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                throw new RuntimeException("PDF file not found: " + filePath);
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load PDF file", e);
        }
    }

    @Override
    public void deletePdf(String filePath) {
        try {
            Path path = Paths.get(filePath);
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete PDF file", e);
        }
    }
}
