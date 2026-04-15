package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.PdfStorageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class PdfStorageServiceImpl implements PdfStorageService {

    private static final String UPLOAD_DIR = "uploads/exams/pdf";

    @Override
    public String savePdf(MultipartFile file) {
        try {
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String uniqueFileName = String.format("exam_%d_%s.pdf",
                    System.currentTimeMillis(),
                    UUID.randomUUID().toString().replace("-", "").substring(0, 8));

            Path filePath = uploadPath.resolve(uniqueFileName);
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
