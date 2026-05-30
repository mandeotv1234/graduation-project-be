package graduation_project_be.application.usecases.request;

import java.util.List;

public record RecordHeartbeatRequest(
        Long examId,
        int seq,
        long clientTs,
        boolean integrityOk,
        List<String> failedChecks) {
}
