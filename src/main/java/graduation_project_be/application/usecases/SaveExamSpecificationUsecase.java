package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.SaveExamSpecificationRequest;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.SpecEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

@RequiredArgsConstructor
public class SaveExamSpecificationUsecase {

    private final ClassRepository classRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public ExamSpecificationResponse execute(SaveExamSpecificationRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();

        Exam exam = examRepository.findById(request.examId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", request.examId()));

        boolean hasAccess = classRepository.existsTeacherAccess(exam.getClassId(), currentUserId);
        if (!hasAccess) {
            throw new UnauthorizedException("You do not have access to this exam");
        }

        Long specificationId = exam.getSpecificationId();
        if (specificationId == null) {
            throw new ResourceNotFoundException("Exam", "specificationId", "null");
        }

        ExamSpecification current = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", specificationId));

        List<SpecEntity> entities = request.entities() == null ? List.of()
                : request.entities().stream().map(e -> {
                    List<SpecAttribute> attributes = e.attributes() == null ? List.of()
                            : e.attributes().stream().map(a -> SpecAttribute.builder()
                                    .attributeName(a.attributeName())
                                    .dataType(a.dataType())
                                    .description(a.description())
                                    .isPrimaryKey(a.isPrimaryKey())
                                    .isNullable(a.isNullable())
                                    .orderIndex(a.orderIndex())
                                    .build()).toList();
                    return SpecEntity.builder()
                            .entityName(e.entityName())
                            .displayName(e.displayName())
                            .description(e.description())
                            .orderIndex(e.orderIndex())
                            .attributes(attributes)
                            .build();
                }).toList();

        LocalDateTime now = LocalDateTime.now();
        List<SaveExamSpecificationRequest.SpecDatasetRequest> requestDatasets =
                request.datasets() == null ? List.of() : request.datasets();
        List<SpecDataset> datasets = IntStream.range(0, requestDatasets.size())
                .mapToObj(index -> {
                    SaveExamSpecificationRequest.SpecDatasetRequest datasetRequest = requestDatasets.get(index);
                    SpecDataset currentDataset = findMatchingDataset(datasetRequest, current.getDatasets());

                    boolean isActive = datasetRequest.isActive() != null
                            ? datasetRequest.isActive()
                            : currentDataset == null || currentDataset.isActive();

                    boolean visibleToStudent = datasetRequest.visibleToStudent() != null
                            ? datasetRequest.visibleToStudent()
                            : currentDataset != null && currentDataset.isVisibleToStudent();

                    return SpecDataset.builder()
                            .id(currentDataset == null ? null : currentDataset.getId())
                            .specificationId(specificationId)
                            .name(datasetRequest.name())
                            .dataScript(datasetRequest.dataScript())
                            .orderIndex(datasetRequest.orderIndex() <= 0 ? index + 1 : datasetRequest.orderIndex())
                            .isActive(isActive)
                            .visibleToStudent(visibleToStudent)
                            .createdAt(currentDataset == null ? now : currentDataset.getCreatedAt())
                            .updatedAt(now)
                            .build();
                })
                .toList();

        boolean ddlVisibleToStudent = request.ddlVisibleToStudent() == null
                ? current.isDdlVisibleToStudent()
                : request.ddlVisibleToStudent();

        ExamSpecification specification = ExamSpecification.builder()
                .id(specificationId)
                .name(request.name())
                .ddlScript(request.ddlScript())
                .ddlVisibleToStudent(ddlVisibleToStudent)
                .description(request.description())
                .entities(entities)
                .datasets(datasets)
                .createdBy(current.getCreatedBy())
                .createdAt(current.getCreatedAt())
                .updatedAt(now)
                .build();

        ExamSpecification saved = examSpecificationRepository.save(specification);
        return ExamSpecificationResponse.fromModel(saved);
    }

    private SpecDataset findMatchingDataset(
            SaveExamSpecificationRequest.SpecDatasetRequest requestDataset,
            List<SpecDataset> currentDatasets) {
        if (currentDatasets == null || currentDatasets.isEmpty()) {
            return null;
        }

        if (requestDataset.id() != null) {
            return currentDatasets.stream()
                    .filter(dataset -> requestDataset.id().equals(dataset.getId()))
                    .findFirst()
                    .orElse(null);
        }

        String name = requestDataset.name() == null ? "" : requestDataset.name().trim();
        if (name.isEmpty()) {
            return null;
        }

        return currentDatasets.stream()
                .filter(dataset -> dataset.getName() != null
                        && name.equalsIgnoreCase(dataset.getName().trim())
                        && (requestDataset.orderIndex() <= 0 || requestDataset.orderIndex() == dataset.getOrderIndex()))
                .findFirst()
                .orElse(null);
    }
}
