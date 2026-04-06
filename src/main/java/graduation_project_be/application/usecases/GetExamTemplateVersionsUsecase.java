package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamTemplateQuestionRepository;
import graduation_project_be.application.port.repositories.ExamTemplateRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.domain.models.ExamTemplate;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetExamTemplateVersionsUsecase {

    private final ExamTemplateRepository examTemplateRepository;
    private final ExamTemplateQuestionRepository examTemplateQuestionRepository;
    private final UserRepository userRepository;

    public record ExamTemplateVersionItem(
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

    public List<ExamTemplateVersionItem> execute(Long sourceExamId) {
        List<ExamTemplate> templates = examTemplateRepository.findVisibleBySourceExamIdOrderByVersionDesc(sourceExamId);

        Map<Long, String> userNames = templates.stream()
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

        return templates.stream()
                .map(template -> new ExamTemplateVersionItem(
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
    }
}
