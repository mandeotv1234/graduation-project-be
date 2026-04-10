package graduation_project_be.application.usecases;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.CreateSpecificationRequest;
import graduation_project_be.application.usecases.response.SpecificationResponse;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
        boolean hasDdlScript = request.ddlScript() != null && !request.ddlScript().isBlank();
        boolean hasSchemaJson = request.schemaJson() != null && !request.schemaJson().isNull();

        if (!hasDdlScript) {
            throw new BadRequestException("ddlScript must be provided");
        }
        if (!hasSchemaJson) {
            throw new BadRequestException("schemaJson must be provided");
        }

        validateSpecificationSchema(request, currentUserId);

        List<SpecDataset> datasets = request.datasets() == null ? List.of() : request.datasets().stream()
                .map(dataset -> SpecDataset.builder()
                        .name(dataset.name())
                        .dataScript(dataset.dataScript())
                        .tableData(dataset.tableData())
                        .orderIndex(dataset.orderIndex())
                        .isActive(dataset.isActive() == null || dataset.isActive())
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList();

        ExamSpecification specification = ExamSpecification.builder()
                .name(request.name())
                .ddlScript(request.ddlScript())
                .schemaJson(request.schemaJson().toString())
                .description(request.description())
                .createdBy(currentUserId)
                .datasets(datasets)
                .createdAt(now)
                .updatedAt(now)
                .build();

        ExamSpecification saved = examSpecificationRepository.save(specification);
        return SpecificationResponse.fromModel(saved);
    }

    private void validateSpecificationSchema(CreateSpecificationRequest request, Long currentUserId) {
        if (request.datasets() == null || request.datasets().isEmpty()) {
            String schemaName = String.format("spec_validation_%d_%d", currentUserId, System.currentTimeMillis());
            try {
                // Load DDL into temporary schema to validate it
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

        // Validate each dataset independently in its own separate schema
        for (int i = 0; i < request.datasets().size(); i++) {
            CreateSpecificationRequest.SpecDatasetRequest dataset = request.datasets().get(i);
            String schemaName = String.format("spec_valid_%d_%d_%d", currentUserId, System.currentTimeMillis(), i);
            try {
                // Load DDL and then the dataset's DML to verify FK/PK/data validity for THIS dataset
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
}
