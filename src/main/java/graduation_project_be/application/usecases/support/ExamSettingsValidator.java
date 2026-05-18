package graduation_project_be.application.usecases.support;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.SpecDataset;

public final class ExamSettingsValidator {

    private ExamSettingsValidator() {
    }

    public static void validateDatabaseInitialization(
            ExamSpecificationRepository examSpecificationRepository,
            Long specificationId,
            ExamSettings settings) {
        if (settings == null || !Boolean.TRUE.equals(settings.getIsLoadDdl())) {
            return;
        }

        if (specificationId == null || specificationId <= 0L) {
            throw new BadRequestException("Exam da bat nap DDL va dataset nhung chua chon dac ta CSDL.");
        }

        Long seedDatasetId = settings.getSeedDatasetId();
        if (seedDatasetId == null) {
            throw new BadRequestException("Exam da bat nap DDL va dataset nhung chua chon dataset.");
        }

        var specification = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new BadRequestException("Khong tim thay dac ta CSDL cua exam."));

        if (specification.getDdlScript() == null || specification.getDdlScript().isBlank()) {
            throw new BadRequestException("Dac ta CSDL dang chon chua co DDL script.");
        }

        boolean validDataset = specification.getDatasets() != null
                && specification.getDatasets().stream()
                        .filter(SpecDataset::isActive)
                        .anyMatch(dataset -> seedDatasetId.equals(dataset.getId())
                                && dataset.getDataScript() != null
                                && !dataset.getDataScript().isBlank());

        if (!validDataset) {
            throw new BadRequestException(
                    "Dataset da chon khong thuoc dac ta CSDL, dang tat, hoac khong co data script.");
        }
    }
}
