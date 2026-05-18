package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.ExamTemplateQuestionRepository;
import graduation_project_be.application.port.repositories.ExamTemplateRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.ShareExamAsTemplateResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.ExamTemplate;
import graduation_project_be.domain.models.ExamTemplateQuestion;
import graduation_project_be.domain.models.ExamTemplateSpecificationSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
public class ShareExamAsTemplateUsecase {

    private final ExamRepository examRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamTemplateRepository examTemplateRepository;
    private final ExamTemplateQuestionRepository examTemplateQuestionRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public ShareExamAsTemplateResponse execute(Long examId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        if (!currentUserId.equals(exam.getCreatorId())) {
            throw new UnauthorizedException("Only the exam creator can publish this exam as a template");
        }

        if (exam.getSpecificationId() == null) {
            throw new BadRequestException("Exam must have a specification before sharing");
        }

        List<ExamQuestion> questions = examQuestionRepository.findByExamId(examId);
        if (questions.isEmpty()) {
            throw new BadRequestException("Exam must have at least one question before sharing");
        }

        ExamSpecification specification = examSpecificationRepository.findById(exam.getSpecificationId())
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", exam.getSpecificationId()));

        ExamTemplate template = ExamTemplate.builder()
                .sourceExamId(examId)
                .version(examTemplateRepository.findNextVersion(examId))
                .sharedBy(currentUserId)
                .title(exam.getTitle())
                .description(exam.getDescription())
                .specificationSnapshot(ExamTemplateSpecificationSnapshot.fromSpecification(specification))
                .createdAt(TimeUtils.now())
                .isVisible(true)
                .build();

        ExamTemplate savedTemplate = examTemplateRepository.save(template);

        List<ExamTemplateQuestion> templateQuestions = questions.stream()
                .map(question -> ExamTemplateQuestion.builder()
                        .templateId(savedTemplate.getId())
                        .content(question.getContent())
                        .correctQuery(question.getCorrectQuery())
                        .verifyScript(question.getVerifyScript())
                        .questionType(question.getQuestionType())
                        .difficultyLevel(question.getDifficultyLevel())
                        .points(question.getPoints())
                        .orderIndex(question.getOrderIndex())
                        .build())
                .toList();

        examTemplateQuestionRepository.saveAll(templateQuestions);

        return new ShareExamAsTemplateResponse(
                savedTemplate.getId(),
                savedTemplate.getSourceExamId(),
                savedTemplate.getVersion(),
                templateQuestions.size()
        );
    }
}
