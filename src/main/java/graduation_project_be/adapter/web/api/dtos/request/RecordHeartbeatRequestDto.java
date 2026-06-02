package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.RecordHeartbeatRequest;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record RecordHeartbeatRequestDto(
        @NotNull(message = "seq is required") Integer seq,
        Long clientTs,
        Boolean integrityOk,
        List<String> failedChecks) {

    public RecordHeartbeatRequest toRequest(Long examId) {
        return new RecordHeartbeatRequest(
                examId,
                seq != null ? seq : 0,
                clientTs != null ? clientTs : 0L,
                integrityOk == null || integrityOk,
                failedChecks != null ? failedChecks : List.of());
    }
}
