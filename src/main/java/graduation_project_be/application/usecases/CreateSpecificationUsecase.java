package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.CreateSpecificationRequest;
import graduation_project_be.application.usecases.response.SpecificationResponse;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.SpecEntity;
import graduation_project_be.domain.models.TableMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class CreateSpecificationUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;

    @Transactional
    public SpecificationResponse execute(CreateSpecificationRequest request) {
        Long currentUserId = currentUserService.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();

        List<SpecDataset> datasets = request.datasets() == null ? List.of() : request.datasets().stream()
                .map(dataset -> SpecDataset.builder()
                        .name(dataset.name())
                        .dataScript(dataset.dataScript())
                        .orderIndex(dataset.orderIndex())
                        .isActive(dataset.isActive() == null || dataset.isActive())
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList();

        ExamSpecification specification = ExamSpecification.builder()
                .name(request.name())
                .ddlScript(request.ddlScript())
                .description(request.description())
                .createdBy(currentUserId)
                .entities(List.of())
                .datasets(datasets)
                .createdAt(now)
                .updatedAt(now)
                .build();

        ExamSpecification saved = examSpecificationRepository.save(specification);

        if (request.ddlScript() != null && !request.ddlScript().isBlank()) {
            saved = generateEntitiesFromDdl(saved);
        }

        return SpecificationResponse.fromModel(saved);
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
