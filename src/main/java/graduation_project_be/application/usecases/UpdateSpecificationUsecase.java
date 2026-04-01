package graduation_project_be.application.usecases;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.transaction.annotation.Transactional;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.CreateSpecificationRequest;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class UpdateSpecificationUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamSchemaService examSchemaService;

    @Transactional
    public ExamSpecificationResponse execute(Long specificationId, CreateSpecificationRequest request) {
        ExamSpecification current = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", specificationId));

        boolean hasDdlScript = request.ddlScript() != null && !request.ddlScript().isBlank();
        if (!hasDdlScript) {
            throw new BadRequestException("ddlScript must be provided");
        }

        validateSpecificationSchema(request, current.getCreatedBy() == null ? 0L : current.getCreatedBy());

        LocalDateTime now = LocalDateTime.now();
        List<SpecDataset> datasets = buildDatasets(request.datasets(), current.getDatasets(), now);

        ExamSpecification specification = ExamSpecification.builder()
                .id(current.getId())
                .name(request.name())
                .ddlScript(request.ddlScript())
                .schemaJson(request.schemaJson() == null ? null : request.schemaJson().toString())
                .description(request.description())
                .datasets(datasets)
                .createdBy(current.getCreatedBy())
                .createdAt(current.getCreatedAt())
                .updatedAt(now)
                .build();

        ExamSpecification saved = examSpecificationRepository.save(specification);
        return ExamSpecificationResponse.fromModel(saved);
    }

    private void validateSpecificationSchema(CreateSpecificationRequest request, Long ownerId) {
        if (request.datasets() == null || request.datasets().isEmpty()) {
            String schemaName = String.format("spec_validation_update_%d_%d", ownerId, System.currentTimeMillis());
            try {
                examSchemaService.loadTemplateIntoSchema(schemaName, request.ddlScript(), null);
            } catch (Exception e) {
                throw new BadRequestException("Invalid specification DDL: " + e.getMessage());
            } finally {
                try {
                    examSchemaService.dropSchema(schemaName);
                } catch (Exception ignored) {}
            }
            return;
        }

        for (int i = 0; i < request.datasets().size(); i++) {
            CreateSpecificationRequest.SpecDatasetRequest dataset = request.datasets().get(i);
            String schemaName = String.format("spec_valid_upd_%d_%d_%d", ownerId, System.currentTimeMillis(), i);
            try {
                examSchemaService.loadTemplateIntoSchema(schemaName, request.ddlScript(), dataset.dataScript());
            } catch (Exception e) {
                throw new BadRequestException("Invalid specification DML in dataset '" + dataset.name() + "': " + e.getMessage());
            } finally {
                try {
                    examSchemaService.dropSchema(schemaName);
                } catch (Exception ignored) {}
            }
        }
    }

    private List<SpecDataset> buildDatasets(
            List<CreateSpecificationRequest.SpecDatasetRequest> datasetRequests,
            List<SpecDataset> currentDatasets,
            LocalDateTime now) {
        if (datasetRequests == null || datasetRequests.isEmpty()) {
            return List.of();
        }

        return IntStream.range(0, datasetRequests.size())
                .mapToObj(datasetIndex -> {
                    CreateSpecificationRequest.SpecDatasetRequest datasetRequest = datasetRequests.get(datasetIndex);
                    SpecDataset currentDataset = findMatchingDataset(datasetRequest, currentDatasets);

                    boolean isActive = datasetRequest.isActive() != null
                            ? datasetRequest.isActive()
                            : currentDataset == null || currentDataset.isActive();

                    return SpecDataset.builder()
                            .id(currentDataset == null ? null : currentDataset.getId())
                            .name(datasetRequest.name())
                            .dataScript(datasetRequest.dataScript())
                            .tableData(datasetRequest.tableData())
                            .orderIndex(datasetRequest.orderIndex() <= 0 ? datasetIndex + 1 : datasetRequest.orderIndex())
                            .isActive(isActive)
                            .createdAt(currentDataset == null ? now : currentDataset.getCreatedAt())
                            .updatedAt(now)
                            .build();
                })
                .toList();
    }

    private SpecDataset findMatchingDataset(
            CreateSpecificationRequest.SpecDatasetRequest requestDataset,
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
