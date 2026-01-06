package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.CreateExamRequest;
import graduation_project_be.application.usecases.response.CreateExamResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CreateExamUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public CreateExamResponse execute(CreateExamRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        boolean isTeacherOfClass = classRepository.existsByIdAndTeacherId(request.classId(), currentUserId);
        if (!isTeacherOfClass) {
            throw new UnauthorizedException("User is not the teacher of this class");
        }

        Exam exam = Exam.builder()
                .templateId(request.templateId())
                .classId(request.classId())
                .creatorId(currentUserId)
                .examMatrix(request.examMatrix())
                .durationMinutes(request.durationMinutes())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .isPublished(request.isPublished() != null ? request.isPublished() : false)
                .build();

        Exam savedExam = examRepository.save(exam);

        return CreateExamResponse.fromModel(savedExam);
    }
}
