package graduation_project_be.infrastructure.persistence.entities;

import graduation_project_be.domain.models.ExamSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExamSettingsJsonTest {

    @Test
    void roundTrip_carriesNewAntiTamperFields() {
        ExamSettings model = ExamSettings.builder()
                .preventCopyPaste(true)
                .heartbeatIntervalSec(8)
                .maxHeartbeatGapSec(25)
                .integrityCheckEnabled(true)
                .requireLockdownBrowser(false)
                .build();

        ExamSettings result = ExamSettingsJson.fromModel(model).toModel();

        assertThat(result.getHeartbeatIntervalSec()).isEqualTo(8);
        assertThat(result.getMaxHeartbeatGapSec()).isEqualTo(25);
        assertThat(result.getIntegrityCheckEnabled()).isTrue();
        assertThat(result.getRequireLockdownBrowser()).isFalse();
        assertThat(result.getPreventCopyPaste()).isTrue();
    }

    @Test
    void legacyJson_missingNewFields_deserializeAsNull() {
        // A settings JSON written before the anti-tamper fields existed → fields stay null.
        ExamSettingsJson legacy = new ExamSettingsJson();
        legacy.setPreventCopyPaste(true);

        ExamSettings model = legacy.toModel();

        assertThat(model.getHeartbeatIntervalSec()).isNull();
        assertThat(model.getMaxHeartbeatGapSec()).isNull();
        assertThat(model.getIntegrityCheckEnabled()).isNull();
        assertThat(model.getRequireLockdownBrowser()).isNull();
    }
}
