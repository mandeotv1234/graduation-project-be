package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.GetExamDraftResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class GetExamDraftUsecase {

    private final ExamRepository examRepository;
    private final ExamDraftRepository examDraftRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;

    public Optional<GetExamDraftResponse> execute(Long examId) {
        Long studentId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new BadRequestException("Exam not found"));

        boolean enrolled = classEnrollmentRepository
                .existsByClassIdAndStudentId(exam.getClassId(), studentId);
        if (!enrolled) {
            throw new BadRequestException("Student is not enrolled in this exam's class");
        }

        return examDraftRepository.findByExamIdAndStudentId(examId, studentId)
                .map(GetExamDraftResponse::fromModel);
    }
}
