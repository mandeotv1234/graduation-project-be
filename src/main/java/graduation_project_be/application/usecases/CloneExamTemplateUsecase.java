package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.ExamTemplateQuestionRepository;
import graduation_project_be.application.port.repositories.ExamTemplateRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.response.CloneExamTemplateResponse;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamTemplate;
import graduation_project_be.domain.models.ExamTemplateQuestion;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
public class CloneExamTemplateUsecase {

    private final ExamTemplateRepository examTemplateRepository;
    private final ExamTemplateQuestionRepository examTemplateQuestionRepository;
    private final ClassRepository classRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final CurrentUserService currentUserService;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamRepository examRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSchemaService examSchemaService;

    @Transactional
    public CloneExamTemplateResponse execute(Long templateId, Long classId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        if (!classRepository.existsTeacherAccess(classId, currentUserId)) {
            throw new UnauthorizedException("User is not a teacher of the target class");
        }

        ExamTemplate template = examTemplateRepository.findVisibleById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamTemplate", "id", templateId));

        LocalDateTime now = TimeUtils.now();
        ExamSpecification clonedSpecification = examSpecificationRepository.save(
                template.getSpecificationSnapshot().toSpecification(currentUserId, now)
        );

        Exam exam = Exam.builder()
                .specificationId(clonedSpecification.getId())
                .classId(classId)
                .creatorId(currentUserId)
                .title(template.getTitle())
                .description(template.getDescription())
                .durationMinutes(60)
                .isPublished(false)
                .createdAt(now)
                .maxAttempts(1)
                .lateThreshold(0)
                .settings(defaultSettings())
                .build();

        Exam savedExam = examRepository.save(exam);

        List<ExamTemplateQuestion> templateQuestions = examTemplateQuestionRepository.findByTemplateId(templateId);
        List<ExamQuestion> newQuestions = templateQuestions.stream()
                .map(question -> ExamQuestion.builder()
                        .examId(savedExam.getId())
                        .content(question.getContent())
                        .correctQuery(question.getCorrectQuery())
                        .verifyScript(question.getVerifyScript())
                        .questionType(question.getQuestionType())
                        .difficultyLevel(question.getDifficultyLevel())
                        .points(question.getPoints())
                        .orderIndex(question.getOrderIndex())
                        .build())
                .toList();

        examQuestionRepository.saveAll(newQuestions);

        List<ClassEnrollment> enrollments = classEnrollmentRepository.findByClassId(classId);
        for (ClassEnrollment enrollment : enrollments) {
            examSchemaService.createExamSchemaForStudent(savedExam.getId(), enrollment.getStudentId());
        }

        return new CloneExamTemplateResponse(savedExam.getId(), savedExam.getTitle(), newQuestions.size());
    }

    private ExamSettings defaultSettings() {
        return ExamSettings.builder()
                .preventCopyPaste(true)
                .forceFullscreen(true)
                .trackTabSwitch(true)
                .autoSubmitOnViolation(false)
                .allowReview(true)
                .scoreDisplayMode("after_closed")
                .allowOvertime(false)
                .gradingMethod("highest_score")
                .build();
    }
}
