package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamTemplateQuestionRepository;
import graduation_project_be.application.port.repositories.ExamTemplateRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.domain.models.ExamTemplate;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetExamTemplatesUsecase {

    private final ExamTemplateRepository examTemplateRepository;
    private final ExamTemplateQuestionRepository examTemplateQuestionRepository;
    private final UserRepository userRepository;

    public record ExamTemplateListItem(
            Long sourceExamId,
            Long latestTemplateId,
            Integer latestVersion,
            int versionCount,
            String title,
            String description,
            String sharedByName,
            int questionCount,
            LocalDateTime latestSharedAt
    ) {
    }

    public List<ExamTemplateListItem> execute() {
        Map<Long, List<ExamTemplate>> groupedTemplates = examTemplateRepository.findVisibleOrderBySourceExamIdAscVersionDesc().stream()
                .collect(Collectors.groupingBy(
                        ExamTemplate::getSourceExamId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<ExamTemplate> latestTemplates = groupedTemplates.values().stream()
                .map(templates -> templates.getFirst())
                .toList();
        Map<Long, Long> questionCounts = examTemplateQuestionRepository.countByTemplateIds(
                latestTemplates.stream()
                        .map(ExamTemplate::getId)
                        .toList()
        );

        Map<Long, String> userNames = latestTemplates.stream()
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

        return groupedTemplates.entrySet().stream()
                .map(entry -> {
                    ExamTemplate latestTemplate = entry.getValue().getFirst();
                    return new ExamTemplateListItem(
                            entry.getKey(),
                            latestTemplate.getId(),
                            latestTemplate.getVersion(),
                            entry.getValue().size(),
                            latestTemplate.getTitle(),
                            latestTemplate.getDescription(),
                            latestTemplate.getSharedBy() == null
                                    ? "Unknown"
                                    : userNames.getOrDefault(latestTemplate.getSharedBy(), "Unknown"),
                            questionCounts.getOrDefault(latestTemplate.getId(), 0L).intValue(),
                            latestTemplate.getCreatedAt()
                    );
                })
                .toList();
    }
}
