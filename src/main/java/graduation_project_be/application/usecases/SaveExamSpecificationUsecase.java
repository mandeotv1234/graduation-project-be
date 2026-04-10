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
import graduation_project_be.application.port.services.ExamSchemaService;
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
    private final ExamSchemaService examSchemaService;

    private static final String TEACHER_SCHEMA_FORMAT = "exam_%d_teacher_%d";

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

        LocalDateTime now = LocalDateTime.now();
        List<SpecEntity> entities = toEntities(request);
        List<SpecDataset> datasets = toDatasets(request, current, specificationId, now);

        ExamSpecification specification = ExamSpecification.builder()
                .id(specificationId)
                .name(current.getName())
                .ddlScript(request.ddlScript())
                .schemaJson(current.getSchemaJson())
                .schemaDiagram(request.schemaDiagram())
                .description(request.description())
                .entities(entities)
                .datasets(datasets)
                .createdBy(current.getCreatedBy())
                .createdAt(current.getCreatedAt())
                .updatedAt(now)
                .build();

        ExamSpecification saved = examSpecificationRepository.save(specification);

        // TODO Feature request from User: Clean up the teacher's sandbox schema (from ExecuteSql)
        // after they finally save the exam, so the DB isn't littered with schemas.
        try {
            String teacherSchemaName = String.format(TEACHER_SCHEMA_FORMAT, request.examId(), currentUserId);
            examSchemaService.dropSchema(teacherSchemaName);
        } catch (Exception e) {
            // Log but don't fail the save transaction if schema drop fails
        }

        return ExamSpecificationResponse.fromModel(saved);
    }

    private List<SpecEntity> toEntities(SaveExamSpecificationRequest request) {
        if (request.entities() == null) {
            return List.of();
        }

        return request.entities().stream()
                .map(entity -> {
                    List<SpecAttribute> attributes = toAttributes(entity.attributes());

                    return SpecEntity.builder()
                            .entityName(entity.entityName())
                            .displayName(entity.displayName())
                            .description(entity.description())
                            .orderIndex(entity.orderIndex())
                            .attributes(attributes)
                            .build();
                })
                .toList();
    }

    private List<SpecAttribute> toAttributes(List<SaveExamSpecificationRequest.SpecAttributeRequest> attributes) {
        if (attributes == null) {
            return List.of();
        }

        return attributes.stream()
                .map(attribute -> SpecAttribute.builder()
                        .attributeName(attribute.attributeName())
                        .dataType(attribute.dataType())
                        .description(attribute.description())
                        .isPrimaryKey(attribute.isPrimaryKey())
                        .isNullable(attribute.isNullable())
                        .orderIndex(attribute.orderIndex())
                        .build())
                .toList();
    }

    private List<SpecDataset> toDatasets(
            SaveExamSpecificationRequest request,
            ExamSpecification current,
            Long specificationId,
            LocalDateTime now) {
        List<SaveExamSpecificationRequest.SpecDatasetRequest> requestDatasets =
                request.datasets() == null ? List.of() : request.datasets();

        return IntStream.range(0, requestDatasets.size())
                .mapToObj(index -> {
                    SaveExamSpecificationRequest.SpecDatasetRequest datasetRequest = requestDatasets.get(index);
                    SpecDataset currentDataset = findMatchingDataset(datasetRequest, current.getDatasets());

                    boolean isActive = datasetRequest.isActive() != null
                            ? datasetRequest.isActive()
                            : currentDataset == null || currentDataset.isActive();

                    return SpecDataset.builder()
                            .id(currentDataset == null ? null : currentDataset.getId())
                            .specificationId(specificationId)
                            .name(datasetRequest.name())
                            .dataScript(datasetRequest.dataScript())
                            .tableData(datasetRequest.tableData())
                            .orderIndex(datasetRequest.orderIndex() <= 0 ? index + 1 : datasetRequest.orderIndex())
                            .isActive(isActive)
                            .createdAt(currentDataset == null ? now : currentDataset.getCreatedAt())
                            .updatedAt(now)
                            .build();
                })
                .toList();
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
