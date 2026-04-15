package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.PdfStorageService;
import graduation_project_be.application.usecases.response.DownloadExamPdfResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DownloadExamPdfUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final PdfStorageService pdfStorageService;

    public DownloadExamPdfResponse execute(Long examId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        if (exam.getPdfFilePath() == null || exam.getPdfFilePath().isBlank()) {
            throw new ResourceNotFoundException("PDF", "examId", examId);
        }

        boolean isTeacher = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        boolean isStudent = classEnrollmentRepository.existsByClassIdAndStudentId(exam.getClassId(), currentUserId);

        if (!isTeacher && !isStudent) {
            throw new UnauthorizedException("You are not authorized to view this PDF");
        }

        byte[] pdfContent = pdfStorageService.loadPdf(exam.getPdfFilePath());

        return new DownloadExamPdfResponse(
                pdfContent,
                exam.getOriginalPdfFileName() != null ? exam.getOriginalPdfFileName() : "exam.pdf"
        );
    }
}
