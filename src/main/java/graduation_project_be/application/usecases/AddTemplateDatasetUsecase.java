package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.SchemaTemplateRepository;
import graduation_project_be.application.port.repositories.TemplateDatasetRepository;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.usecases.request.AddTemplateDatasetRequest;
import graduation_project_be.domain.models.SchemaTemplate;
import graduation_project_be.domain.models.TemplateDataset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

@Slf4j
@RequiredArgsConstructor
public class AddTemplateDatasetUsecase {

        private final SchemaTemplateRepository schemaTemplateRepository;
        private final TemplateDatasetRepository templateDatasetRepository;
        private final ExamSchemaService examSchemaService;

        public TemplateDataset execute(AddTemplateDatasetRequest request) {
                SchemaTemplate template = schemaTemplateRepository.findById(request.templateId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "SchemaTemplate not found: " + request.templateId()));

                var existingDatasets = templateDatasetRepository.findByTemplateId(request.templateId());
                int nextOrder = existingDatasets.size() + 1;

                TemplateDataset dataset = TemplateDataset.builder()
                                .templateId(request.templateId())
                                .dataScript(request.dataScript())
                                .orderIndex(nextOrder)
                                .createdAt(LocalDateTime.now())
                                .build();

                TemplateDataset saved = templateDatasetRepository.save(dataset);

                String schemaName = String.format("tpl_%d_ds_%d", template.getId(), saved.getId());
                saved.setSchemaName(schemaName);
                saved = templateDatasetRepository.save(saved);

                log.info("Creating reference schema [{}] for template {} dataset {}",
                                schemaName, template.getId(), saved.getId());
                examSchemaService.loadTemplateIntoSchema(schemaName, template.getDdlScript(), saved.getDataScript());

                return saved;
        }
}
