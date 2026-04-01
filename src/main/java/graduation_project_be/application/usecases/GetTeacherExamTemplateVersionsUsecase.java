package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamTemplateQuestionRepository;
import graduation_project_be.application.port.repositories.ExamTemplateRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamTemplate;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetTeacherExamTemplateVersionsUsecase {

    private final ExamRepository examRepository;
    private final ExamTemplateRepository examTemplateRepository;
    private final ExamTemplateQuestionRepository examTemplateQuestionRepository;
    private final ClassRepository classRepository;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;

    public record TeacherExamTemplateVersionItem(
            Long templateId,
            Long sourceExamId,
            Integer version,
            String title,
            String description,
            String sharedByName,
            int questionCount,
            LocalDateTime createdAt,
            boolean isVisible
    ) {
    }

    public record TeacherExamTemplateVersionsResult(
            boolean canManage,
            List<TeacherExamTemplateVersionItem> versions
    ) {
    }

    public TeacherExamTemplateVersionsResult execute(Long examId) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam sourceExam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        boolean canManage = currentUserId.equals(sourceExam.getCreatorId());
        boolean canView = canManage || classRepository.existsTeacherAccess(sourceExam.getClassId(), currentUserId);

        if (!canView) {
            throw new UnauthorizedException("User does not have access to this exam template history");
        }

        List<ExamTemplate> templates = examTemplateRepository.findBySourceExamIdOrderByVersionDesc(examId);
        List<ExamTemplate> visibleTemplates = canManage
                ? templates
                : templates.stream()
                .filter(template -> Boolean.TRUE.equals(template.getIsVisible()))
                .toList();

        Map<Long, String> userNames = visibleTemplates.stream()
                .map(ExamTemplate::getSharedBy)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        ids -> ids.isEmpty()
                                ? Map.<Long, String>of()
                                : userRepository.findByIdIn(ids, ids.size(), 0).stream()
                                .collect(Collectors.toMap(User::getId, User::getFullName, (left, right) -> left))
                ));

        List<TeacherExamTemplateVersionItem> versions = visibleTemplates.stream()
                .map(template -> new TeacherExamTemplateVersionItem(
                        template.getId(),
                        template.getSourceExamId(),
                        template.getVersion(),
                        template.getTitle(),
                        template.getDescription(),
                        template.getSharedBy() == null
                                ? "Unknown"
                                : userNames.getOrDefault(template.getSharedBy(), "Unknown"),
                        (int) examTemplateQuestionRepository.countByTemplateId(template.getId()),
                        template.getCreatedAt(),
                        Boolean.TRUE.equals(template.getIsVisible())))
                .toList();

        return new TeacherExamTemplateVersionsResult(canManage, versions);
    }
}
