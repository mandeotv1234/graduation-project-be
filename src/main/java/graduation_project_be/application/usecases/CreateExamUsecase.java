package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.CreateExamRequest;
import graduation_project_be.application.usecases.response.CreateExamResponse;
import graduation_project_be.application.usecases.support.ExamSettingsValidator;
import graduation_project_be.domain.models.Exam;
import lombok.RequiredArgsConstructor;

import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class CreateExamUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final ExamSpecificationRepository examSpecificationRepository;

    @Transactional
    public CreateExamResponse execute(CreateExamRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        boolean isTeacherOfClass = classRepository.existsTeacherAccess(request.classId(), currentUserId);
        if (!isTeacherOfClass) {
            throw new UnauthorizedException("User is not the teacher of this class");
        }

        Long specificationId = request.specificationId();
        if (specificationId != null && specificationId <= 0L) {
            specificationId = null;
        }

        // Đặc tả (spec) và PDF có thể tồn tại đồng thời: spec cấp schema/IntelliSense
        // và dùng để chấm, PDF là tài liệu đề bài hiển thị cho sinh viên.
        boolean hasPdf = request.pdfFilePath() != null && !request.pdfFilePath().isBlank();

        ExamSettingsValidator.validateDatabaseInitialization(
                examSpecificationRepository,
                specificationId,
                request.settings());
        ExamSettingsValidator.validateExamTimeWindow(request.startTime(), request.endTime());

        Exam exam = Exam.builder()
                .specificationId(specificationId)
                .classId(request.classId())
                .creatorId(currentUserId)
                .title(request.title())
                .durationMinutes(request.durationMinutes())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .isPublished(request.isPublished() != null ? request.isPublished() : false)
                .createdAt(TimeUtils.now())
                .description(request.description())
                .maxAttempts(request.maxAttempts() != null ? request.maxAttempts() : 1)
                .lateThreshold(request.lateThreshold() != null ? request.lateThreshold() : 0)
                .settings(request.settings())
                .pdfFilePath(hasPdf ? request.pdfFilePath() : null)
                .originalPdfFileName(hasPdf ? request.originalPdfFileName() : null)
                .build();

        Exam savedExam = examRepository.save(exam);

        return CreateExamResponse.fromModel(savedExam);
    }
}
