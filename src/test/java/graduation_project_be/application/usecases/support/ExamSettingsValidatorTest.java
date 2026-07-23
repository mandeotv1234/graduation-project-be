package graduation_project_be.application.usecases.support;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.domain.models.ExamSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExamSettingsValidatorTest {

    private static final LocalDateTime START_TIME = LocalDateTime.of(2026, 7, 23, 8, 0);

    @Test
    void acceptsTimeWindowEqualToExamDuration() {
        assertThatCode(() -> ExamSettingsValidator.validateExamConfiguration(
                "Kiểm tra giữa kỳ",
                60,
                START_TIME,
                START_TIME.plusMinutes(60),
                1,
                0,
                ExamSettings.builder().allowOvertime(false).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTimeWindowShorterThanExamDuration() {
        assertThatThrownBy(() -> ExamSettingsValidator.validateExamConfiguration(
                "Kiểm tra giữa kỳ",
                60,
                START_TIME,
                START_TIME.plusMinutes(59),
                1,
                0,
                ExamSettings.builder().allowOvertime(false).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ít nhất bằng thời lượng");
    }

    @Test
    void rejectsIncompleteTimeWindow() {
        assertThatThrownBy(() -> ExamSettingsValidator.validateExamConfiguration(
                "Kiểm tra giữa kỳ",
                60,
                START_TIME,
                null,
                1,
                0,
                ExamSettings.builder().allowOvertime(false).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("đầy đủ");
    }

    @Test
    void rejectsInvalidExamLimitsAndSettings() {
        assertThatThrownBy(() -> ExamSettingsValidator.validateExamConfiguration(
                "Kiểm tra giữa kỳ",
                241,
                null,
                null,
                100,
                241,
                ExamSettings.builder().allowOvertime(false).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("240 phút");

        assertThatThrownBy(() -> ExamSettingsValidator.validateExamConfiguration(
                "Kiểm tra giữa kỳ",
                60,
                null,
                null,
                1,
                0,
                ExamSettings.builder()
                        .heartbeatIntervalSec(20)
                        .maxHeartbeatGapSec(20)
                        .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("heartbeat");
    }

    @Test
    void validatesQuestionPointsMetadataAndTotal() {
        assertThatCode(() -> {
            ExamSettingsValidator.validateQuestionPoints(new BigDecimal("0.10"));
            ExamSettingsValidator.validateQuestionMetadata(5, 1);
            ExamSettingsValidator.validateTotalPoints(
                    List.of(new BigDecimal("4.25"), new BigDecimal("5.75")));
        }).doesNotThrowAnyException();

        assertThatThrownBy(() ->
                ExamSettingsValidator.validateQuestionPoints(new BigDecimal("0.001")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() ->
                ExamSettingsValidator.validateQuestionMetadata(6, 1))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ExamSettingsValidator.validateTotalPoints(
                List.of(new BigDecimal("6"), new BigDecimal("4.1"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("10 điểm");
    }

    @Test
    void rejectsUnknownSpecificationEvenWhenDdlLoadingIsDisabled() {
        ExamSpecificationRepository repository = mock(ExamSpecificationRepository.class);
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ExamSettingsValidator.validateDatabaseInitialization(
                repository,
                999L,
                ExamSettings.builder().isLoadDdl(false).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Không tìm thấy đặc tả");
    }
}
