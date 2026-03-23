package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.CreateSpecificationRequest;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.SpecEntity;
import graduation_project_be.domain.models.TableMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class UpdateSpecificationUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamSchemaService examSchemaService;

    @Transactional
    public ExamSpecificationResponse execute(Long specificationId, CreateSpecificationRequest request) {
        ExamSpecification current = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", specificationId));

        boolean hasEntitiesFromRequest = request.entities() != null && !request.entities().isEmpty();
        boolean hasDdlScript = request.ddlScript() != null && !request.ddlScript().isBlank();
        if (!hasEntitiesFromRequest && !hasDdlScript) {
            throw new BadRequestException("Either entities or ddlScript must be provided");
        }

        LocalDateTime now = LocalDateTime.now();
        List<SpecDataset> datasets = buildDatasets(request.datasets(), now);
        List<SpecEntity> entities = buildEntities(request.entities());

        ExamSpecification specification = ExamSpecification.builder()
                .id(current.getId())
                .name(request.name())
                .ddlScript(request.ddlScript())
                .description(request.description())
                .entities(entities)
                .datasets(datasets)
                .createdBy(current.getCreatedBy())
                .createdAt(current.getCreatedAt())
                .updatedAt(now)
                .build();

        ExamSpecification saved = examSpecificationRepository.save(specification);
        if (!hasEntitiesFromRequest && hasDdlScript) {
            saved = generateEntitiesFromDdl(saved);
        }

        return ExamSpecificationResponse.fromModel(saved);
    }

    private List<SpecDataset> buildDatasets(
            List<CreateSpecificationRequest.SpecDatasetRequest> datasetRequests,
            LocalDateTime now) {
        if (datasetRequests == null || datasetRequests.isEmpty()) {
            return List.of();
        }

        List<SpecDataset> datasets = new ArrayList<>();
        for (int datasetIndex = 0; datasetIndex < datasetRequests.size(); datasetIndex++) {
            CreateSpecificationRequest.SpecDatasetRequest dataset = datasetRequests.get(datasetIndex);
            datasets.add(SpecDataset.builder()
                    .name(dataset.name())
                    .dataScript(dataset.dataScript())
                    .orderIndex(dataset.orderIndex() <= 0 ? datasetIndex + 1 : dataset.orderIndex())
                    .isActive(dataset.isActive() == null || dataset.isActive())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        return datasets;
    }

    private List<SpecEntity> buildEntities(List<CreateSpecificationRequest.SpecEntityRequest> entityRequests) {
        if (entityRequests == null || entityRequests.isEmpty()) {
            return List.of();
        }

        List<SpecEntity> entities = new ArrayList<>();
        for (int entityIndex = 0; entityIndex < entityRequests.size(); entityIndex++) {
            CreateSpecificationRequest.SpecEntityRequest entityRequest = entityRequests.get(entityIndex);
            if (entityRequest.entityName() == null || entityRequest.entityName().isBlank()) {
                throw new BadRequestException("Entity name is required");
            }

            List<SpecAttribute> attributes = new ArrayList<>();
            List<CreateSpecificationRequest.SpecAttributeRequest> attributeRequests = entityRequest.attributes();
            if (attributeRequests != null) {
                for (int attributeIndex = 0; attributeIndex < attributeRequests.size(); attributeIndex++) {
                    CreateSpecificationRequest.SpecAttributeRequest attributeRequest = attributeRequests.get(attributeIndex);
                    if (attributeRequest.attributeName() == null || attributeRequest.attributeName().isBlank()) {
                        throw new BadRequestException("Attribute name is required");
                    }
                    if (attributeRequest.dataType() == null || attributeRequest.dataType().isBlank()) {
                        throw new BadRequestException("Attribute dataType is required");
                    }

                    attributes.add(SpecAttribute.builder()
                            .attributeName(attributeRequest.attributeName())
                            .dataType(attributeRequest.dataType())
                            .description(attributeRequest.description())
                            .isPrimaryKey(attributeRequest.isPrimaryKey() != null && attributeRequest.isPrimaryKey())
                            .isNullable(attributeRequest.isNullable() == null || attributeRequest.isNullable())
                            .orderIndex(attributeRequest.orderIndex() == null ? attributeIndex + 1
                                    : attributeRequest.orderIndex())
                            .build());
                }
            }

            entities.add(SpecEntity.builder()
                    .entityName(entityRequest.entityName())
                    .displayName(entityRequest.displayName() == null || entityRequest.displayName().isBlank()
                            ? entityRequest.entityName()
                            : entityRequest.displayName())
                    .description(entityRequest.description())
                    .orderIndex(entityRequest.orderIndex() == null ? entityIndex + 1 : entityRequest.orderIndex())
                    .attributes(attributes)
                    .build());
        }

        return entities;
    }

    private ExamSpecification generateEntitiesFromDdl(ExamSpecification specification) {
        String tempSchemaName = "TEMP_SPEC_" + specification.getId() + "_" + System.currentTimeMillis();

        try {
            examSchemaService.loadTemplateIntoSchema(tempSchemaName, specification.getDdlScript(), null);
            List<TableMetadata> tables = examSchemaService.extractMetadata(tempSchemaName);

            List<SpecEntity> entities = tables.stream()
                    .map(table -> {
                        List<SpecAttribute> attributes = table.getColumns().stream()
                                .map(col -> SpecAttribute.builder()
                                        .attributeName(col.getColumnName())
                                        .dataType(col.getDataType())
                                        .isNullable(col.isNullable())
                                        .isPrimaryKey(col.isPrimaryKey())
                                        .build())
                                .collect(Collectors.toList());

                        return SpecEntity.builder()
                                .entityName(table.getTableName())
                                .displayName(table.getTableName())
                                .attributes(attributes)
                                .build();
                    })
                    .collect(Collectors.toList());

            ExamSpecification updated = ExamSpecification.builder()
                    .id(specification.getId())
                    .name(specification.getName())
                    .ddlScript(specification.getDdlScript())
                    .description(specification.getDescription())
                    .createdBy(specification.getCreatedBy())
                    .entities(entities)
                    .datasets(specification.getDatasets())
                    .createdAt(specification.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .build();

            return examSpecificationRepository.save(updated);
        } catch (Exception e) {
            log.error("Failed to auto-generate entities for specification ID: {}. Error: {}",
                    specification.getId(), e.getMessage());
            return specification;
        } finally {
            try {
                examSchemaService.dropSchema(tempSchemaName);
            } catch (Exception e) {
                log.error("Failed to drop temporary schema: {}", tempSchemaName, e);
            }
        }
    }
}
