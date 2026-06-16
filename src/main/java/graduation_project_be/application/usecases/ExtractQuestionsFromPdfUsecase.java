package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.port.services.PdfStorageService;
import graduation_project_be.application.usecases.request.ExtractQuestionsFromPdfRequest;
import graduation_project_be.application.usecases.response.ExtractQuestionsFromPdfResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ExtractQuestionsFromPdfUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final PdfStorageService pdfStorageService;
    private final CurrentUserService currentUserService;
    private final AIService aiService;

    public ExtractQuestionsFromPdfResponse execute(ExtractQuestionsFromPdfRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new IllegalArgumentException("Exam not found with id: " + request.examId()));

        if (!classRepository.existsTeacherAccess(exam.getClassId(), currentUserId)) {
            throw new UnauthorizedException("You do not have access to this exam");
        }

        if (exam.getPdfFilePath() == null || exam.getPdfFilePath().isBlank()) {
            throw new BadRequestException("This exam does not have an uploaded PDF. Upload a PDF when creating or editing the exam first.");
        }

        byte[] pdfBytes = pdfStorageService.loadPdf(exam.getPdfFilePath());
        String schemaContext = buildSchemaContext(exam);

        AIService.PdfExtractionResult extracted =
                aiService.extractQuestionsFromPdf(pdfBytes, schemaContext);

        if (extracted == null
                || ((extracted.questions() == null || extracted.questions().isEmpty())
                && (extracted.schemaScript() == null || extracted.schemaScript().isBlank()))) {
            throw new BadRequestException("Không trích xuất được câu hỏi từ PDF. "
                    + "PDF có thể là ảnh scan khó đọc hoặc model AI hiện tại không hỗ trợ đọc ảnh PDF.");
        }

        String schemaScript = extracted.schemaScript() != null ? extracted.schemaScript() : "";
        if (!schemaScript.isBlank() && exam.getSpecificationId() != null) {
            persistExtractedDdl(exam.getSpecificationId(), schemaScript);
        }

        List<AIService.ExtractedQuestion> extractedQuestions =
                extracted.questions() != null ? extracted.questions() : List.of();
        List<ExtractQuestionsFromPdfResponse.QuestionDraft> drafts = extractedQuestions.stream()
                .map(q -> new ExtractQuestionsFromPdfResponse.QuestionDraft(
                        q.title(), q.content(), q.questionType(), q.points(), q.difficultyLevel(), q.orderIndex()))
                .toList();

        return new ExtractQuestionsFromPdfResponse(drafts, schemaScript);
    }

    private void persistExtractedDdl(Long specificationId, String schemaScript) {
        try {
            examSpecificationRepository.findById(specificationId).ifPresent(spec -> {
                spec.setDdlScript(schemaScript);
                examSpecificationRepository.save(spec);
                log.info("Updated ExamSpecification {} ddlScript from PDF extraction ({} chars)",
                        specificationId, schemaScript.length());
            });
        } catch (Exception e) {
            log.warn("Failed to persist extracted DDL to ExamSpecification {}: {}", specificationId, e.getMessage());
        }
    }

    private String buildSchemaContext(Exam exam) {
        if (exam.getSpecificationId() == null) {
            return "";
        }
        return examSpecificationRepository.findById(exam.getSpecificationId())
                .map(ExamSpecification::getDdlScript)
                .filter(ddl -> ddl != null && !ddl.isBlank())
                .orElse("");
    }
}
