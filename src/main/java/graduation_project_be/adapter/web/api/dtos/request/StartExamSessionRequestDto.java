package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.StartExamSessionRequest;

public record StartExamSessionRequestDto() {

    public StartExamSessionRequest toRequest(Long examId, String ipAddress, String userAgent) {
        return new StartExamSessionRequest(examId, ipAddress, userAgent);
    }
}
