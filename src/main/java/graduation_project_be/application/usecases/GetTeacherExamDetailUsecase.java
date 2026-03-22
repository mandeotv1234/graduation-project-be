package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GetTeacherExamDetailRequest;
import graduation_project_be.application.usecases.response.GetTeacherExamDetailResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class GetTeacherExamDetailUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public GetTeacherExamDetailResponse execute(GetTeacherExamDetailRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Long examId = request.examId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // Check if user is teacher of the class
        boolean isTeacherOfClass = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        if (!isTeacherOfClass) {
            throw new UnauthorizedException("User is not authorized to view this exam detail");
        }

        return GetTeacherExamDetailResponse.fromModel(exam);
    }
}
