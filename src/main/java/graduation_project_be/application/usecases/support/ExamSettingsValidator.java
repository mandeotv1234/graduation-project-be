package graduation_project_be.application.usecases.support;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.SpecDataset;

import java.time.LocalDateTime;

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
            throw new BadRequestException("Đề thi đã bật nạp DDL và dataset nhưng chưa chọn đặc tả CSDL.");
        }

        Long seedDatasetId = settings.getSeedDatasetId();
        if (seedDatasetId == null) {
            throw new BadRequestException("Đề thi đã bật nạp DDL và dataset nhưng chưa chọn dataset.");
        }

        var specification = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new BadRequestException("Không tìm thấy đặc tả CSDL của đề thi."));

        if (specification.getDdlScript() == null || specification.getDdlScript().isBlank()) {
            throw new BadRequestException("Đặc tả CSDL đang chọn chưa có DDL script.");
        }

        boolean validDataset = specification.getDatasets() != null
                && specification.getDatasets().stream()
                        .filter(SpecDataset::isActive)
                        .anyMatch(dataset -> seedDatasetId.equals(dataset.getId())
                                && dataset.getDataScript() != null
                                && !dataset.getDataScript().isBlank());

        if (!validDataset) {
            throw new BadRequestException(
                    "Dataset đã chọn không thuộc đặc tả CSDL, đang tắt, hoặc không có data script.");
        }
    }

    public static void validateExamTimeWindow(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return;
        }
        if (!endTime.isAfter(startTime)) {
            throw new BadRequestException("Thời gian kết thúc phải sau thời gian bắt đầu.");
        }
    }
}
