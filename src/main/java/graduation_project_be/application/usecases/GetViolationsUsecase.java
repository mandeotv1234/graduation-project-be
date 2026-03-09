package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamViolationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.ExamViolationResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetViolationsUsecase {

    private final ExamViolationRepository examViolationRepository;
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;

    public List<ExamViolationResponse> execute(Long examId, Long studentId) {
        Long teacherId = currentUserService.getCurrentUserId();

        // Validate exam exists and teacher is the creator
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found"));

        if (!exam.getCreatorId().equals(teacherId)) {
            throw new IllegalArgumentException("Only the exam creator can view violations");
        }

        if (studentId != null) {
            return examViolationRepository.findByExamIdAndStudentId(examId, studentId).stream()
                    .map(ExamViolationResponse::fromModel)
                    .toList();
        }

        return examViolationRepository.findByExamId(examId).stream()
                .map(ExamViolationResponse::fromModel)
                .toList();
    }
}
