package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.TemplateDataset;
import java.util.List;

public interface TemplateDatasetRepository {
    TemplateDataset save(TemplateDataset dataset);

    List<TemplateDataset> findByTemplateId(Long templateId);
}
