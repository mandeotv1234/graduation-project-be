package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.UpdateExamRequest;
import graduation_project_be.application.usecases.response.UpdateExamResponse;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class UpdateExamUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;

    public UpdateExamResponse execute(UpdateExamRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Long examId = request.examId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // Check if user is teacher of the class
        boolean isTeacherOfClass = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        if (!isTeacherOfClass) {
            throw new UnauthorizedException("User is not authorized to update this exam");
        }

        // Update fields if provided
        if (request.title() != null) exam.setTitle(request.title());
        if (request.specificationId() != null) exam.setSpecificationId(request.specificationId());
        if (request.durationMinutes() != null) exam.setDurationMinutes(request.durationMinutes());
        if (request.startTime() != null) exam.setStartTime(request.startTime());
        if (request.endTime() != null) exam.setEndTime(request.endTime());
        if (request.isPublished() != null) exam.setIsPublished(request.isPublished());
        if (request.description() != null) exam.setDescription(request.description());
        if (request.maxAttempts() != null) exam.setMaxAttempts(request.maxAttempts());
        if (request.lateThreshold() != null) exam.setLateThreshold(request.lateThreshold());
        if (request.settings() != null) exam.setSettings(request.settings());

        Exam savedExam = examRepository.save(exam);
        log.info("Exam updated successfully: {}", savedExam.getId());

        return UpdateExamResponse.fromModel(savedExam);
    }
}
